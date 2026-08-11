import {memo, useCallback, useEffect, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {del, getJson, postJson, type StreamEvent} from '../api';
import {marked} from 'marked';
import DOMPurify from 'dompurify';
import type {AttachmentPayload, ChatMessage, PendingConfirm, QueueItem} from '../chatStore';
import {
    chatKey,
    getActiveSession,
    getChatSession,
    setActiveSession,
    updateChatSession,
    useChatSession
} from '../chatStore';
import {getChatSocket, subscribeChatSocket} from '../chatSocket';

// ============ 类型 ============
interface Workspace { workspaceId: string; name: string; agentName?: string; path: string; description: string; }
interface SessionItem { id: string; workspaceId: string; title: string; createdAt: string; }
interface BoxMessage {
  id?: string; type: string; content: string; toolName?: string;
  toolArgs?: string; toolResult?: string; subagentName?: string; images?: string[]; seq: number;
}
interface Attachment { name: string; mimeType: string; base64Data: string; }

interface FileEntry { name: string; path: string; directory: boolean; size: number; modifiedAt: number; }

const TEXT_EXTS = new Set(['txt','md','markdown','log','json','yaml','yml','toml','xml','html','htm','css','scss','less','js','jsx','ts','tsx','mjs','cjs','java','kt','kts','groovy','scala','py','pyi','rb','php','go','rs','swift','c','h','cpp','hpp','cs','sh','bash','zsh','bat','cmd','ps1','psm1','sql','properties','ini','cfg','conf','gradle','sbt','makefile','dockerfile','vue','svelte','astro']);
const IMAGE_EXTS = new Set(['png','jpg','jpeg','gif','webp','bmp','svg','ico','tiff']);

function extOf(name: string): string {
  const i = name.lastIndexOf('.');
  return i >= 0 ? name.slice(i + 1).toLowerCase() : '';
}

function fileKind(entry: FileEntry): 'dir' | 'text' | 'image' | 'binary' {
  if (entry.directory) return 'dir';
  const ext = extOf(entry.name);
  if (IMAGE_EXTS.has(ext)) return 'image';
  if (TEXT_EXTS.has(ext) || entry.size <= 4096) return 'text';
  return 'binary';
}

function fileIcon(entry: FileEntry): string {
  const k = fileKind(entry);
  if (k === 'dir') return '📂';
  const ext = extOf(entry.name);
  if (['java','kt','kts'].includes(ext)) return '☕';
  if (['py','pyi'].includes(ext)) return '🐍';
  if (['js','jsx','mjs','cjs'].includes(ext)) return '🟨';
  if (['ts','tsx'].includes(ext)) return '🟦';
  if (['html','htm'].includes(ext)) return '🌐';
  if (['css','scss','less'].includes(ext)) return '🎨';
  if (['json','yaml','yml','toml'].includes(ext)) return '⚙️';
  if (['md','markdown'].includes(ext)) return '📝';
  if (['svg'].includes(ext)) return '🖼️';
  if (['png','jpg','jpeg','gif','webp','bmp','ico','tiff'].includes(ext)) return '🖼️';
  if (['go'].includes(ext)) return '🐹';
  if (['rs'].includes(ext)) return '🦀';
  if (['sh','bash','zsh','bat','cmd','ps1','psm1'].includes(ext)) return '💻';
  if (['c','h','cpp','hpp'].includes(ext)) return '🔧';
  if (['sql'].includes(ext)) return '🗄️';
  if (['log'].includes(ext)) return '📋';
  if (k === 'text') return '📄';
  if (k === 'image') return '🖼️';
  return '📦';
}

function fileColorClass(entry: FileEntry): string {
  const k = fileKind(entry);
  if (k === 'dir') return 'fc-dir';
  if (k === 'text') return 'fc-text';
  if (k === 'image') return 'fc-image';
  return 'fc-binary';
}

function formatSize(bytes: number): string {
  if (bytes === 0) return '';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
}

const mdCache = new Map<string, string>();
const mdCacheMax = 256;

// 收到这些事件说明 Agent 正在输出，可用于重连后自动恢复 running 状态
const DATA_EVENT_TYPES = new Set(['text', 'reasoning', 'tool', 'tool_args', 'tool_result', 'subagent', 'subagent_text']);
function md(raw: string): string {
  if (!raw) return '';
  const cached = mdCache.get(raw);
  if (cached !== undefined) return cached;
  const html = DOMPurify.sanitize(marked.parse(raw, { breaks: true }) as string);
  if (mdCache.size >= mdCacheMax) {
    const firstKey = mdCache.keys().next().value;
    if (firstKey !== undefined) mdCache.delete(firstKey);
  }
  mdCache.set(raw, html);
  return html;
}

// 流式事件 → 消息列表（纯 reducer，可批量应用）
// 注意：必须不可变更新（每次创建新消息对象），否则 React.memo 按引用比较会跳过重渲染
function reduceMessage(prev: ChatMessage[], evt: StreamEvent): ChatMessage[] {
  const next = [...prev];
  const lastIdx = next.length - 1;
  const last = next[lastIdx];
  switch (evt.type) {
    case 'text': {
      if (last && last.role === 'ai') {
        const segs = [...last.segments];
        const li = segs.length - 1;
        if (li >= 0 && segs[li].type === 'text') {
          segs[li] = { ...segs[li], content: segs[li].content + evt.content };
        } else {
          segs.push({ type: 'text', content: evt.content });
        }
        next[lastIdx] = { ...last, segments: segs };
      } else {
        next.push({ role: 'ai', segments: [{ type: 'text', content: evt.content }] });
      }
      break;
    }
    case 'reasoning': {
      if (last && last.role === 'ai') {
        const segs = [...last.segments];
        const li = segs.length - 1;
        if (li >= 0 && segs[li].type === 'reasoning') {
          segs[li] = { ...segs[li], content: segs[li].content + evt.content };
        } else {
          segs.push({ type: 'reasoning', content: evt.content });
        }
        next[lastIdx] = { ...last, segments: segs };
      } else {
        next.push({ role: 'ai', segments: [{ type: 'reasoning', content: evt.content }] });
      }
      break;
    }
    case 'tool': {
      if (last && last.role === 'ai') {
        const segs = [...last.segments];
        const li = segs.length - 1;
        if (li >= 0 && segs[li].type === 'tool') {
          segs[li] = { ...segs[li], content: segs[li].content + `\n\n── ${evt.content} ──` };
        } else {
          segs.push({ type: 'tool', content: `── ${evt.content} ──` });
        }
        next[lastIdx] = { ...last, segments: segs };
      } else {
        next.push({ role: 'ai', segments: [{ type: 'tool', content: `── ${evt.content} ──` }] });
      }
      break;
    }
    case 'tool_args': {
      if (last && last.role === 'ai') {
        const segs = [...last.segments];
        const li = segs.length - 1;
        if (li >= 0 && segs[li].type === 'tool') {
          segs[li] = { ...segs[li], content: segs[li].content + `\n参数: ${evt.content}` };
          next[lastIdx] = { ...last, segments: segs };
        }
      }
      break;
    }
    case 'tool_result': {
      if (last && last.role === 'ai') {
        const segs = [...last.segments];
        const li = segs.length - 1;
        if (li >= 0 && segs[li].type === 'tool') {
          segs[li] = { ...segs[li], content: segs[li].content + `\n📤 结果: ${evt.content}` };
          next[lastIdx] = { ...last, segments: segs };
        }
      }
      break;
    }
    case 'subagent': {
      if (last && last.role === 'ai') {
        const segs = [...last.segments, { type: 'subagent', name: evt.content, content: '' }];
        next[lastIdx] = { ...last, segments: segs };
      } else {
        next.push({ role: 'ai', segments: [{ type: 'subagent', name: evt.content, content: '' }] });
      }
      break;
    }
    case 'subagent_text': {
      const sep = evt.content.indexOf('\u0001');
      const name = sep >= 0 ? evt.content.slice(0, sep) : '';
      const delta = sep >= 0 ? evt.content.slice(sep + 1) : evt.content;
      if (last && last.role === 'ai') {
        const segs = last.segments.map((sg) =>
          sg.type === 'subagent' && sg.name === name ? { ...sg, content: sg.content + delta } : sg,
        );
        if (!last.segments.some((sg) => sg.type === 'subagent' && sg.name === name)) {
          segs.push({ type: 'subagent', name, content: delta });
        }
        next[lastIdx] = { ...last, segments: segs };
      }
      break;
    }
    case 'context':
    case 'auto_confirm':
    default:
      break;
  }
  return next;
}

// ============ 消息渲染 ============
function FoldBlock({ title, children, className, loading }: {
  title: string; children: string; className?: string; loading?: boolean;
}) {
  return (
    <details className={`fold ${className || ''} ${loading ? 'loading' : ''}`} open={loading}>
      <summary>
        {loading && <span className="spinner-small" />}
        {title}
      </summary>
      <div className="fold-body">{children}</div>
    </details>
  );
}

const AiMessage = memo(function AiMessage({ msg, isStreaming, agentLabel, activeTools, activeSubagents }: {
  msg: ChatMessage; isStreaming?: boolean; agentLabel?: string;
  activeTools: string[]; activeSubagents: string[];
}) {
  const toolIdxRef = useRef(0);
  toolIdxRef.current = 0;
  const label = agentLabel || 'AI';
  // 判断某个 tool segment 是否正在执行：检查 activeTools 中是否包含该工具名
  const isToolActive = (segContent: string): boolean => {
    for (const t of activeTools) {
      if (segContent.includes(t)) return true;
    }
    return false;
  };
  const isSubagentActive = (name: string): boolean => activeSubagents.includes(name);
  return (
    <div className="chat-block ai">
      <div className="chat-meta">🤖 {label}</div>
      <div className="chat-row">
        <div className="chat-avatar">🤖</div>
        <div className="chat-bubble">
          {msg.segments.map((seg, i) => {
            switch (seg.type) {
              case 'text':
                return isStreaming ? (
                  <div key={i} className="md-content-wrap">
                    <div className="md-content" style={{ whiteSpace: 'pre-wrap' }}>{seg.content}</div>
                    {i === msg.segments.length - 1 ? <span className="typing-cursor" /> : null}
                  </div>
                ) : (
                  <div key={i} className="md-content-wrap">
                    <div className="md-content" dangerouslySetInnerHTML={{ __html: md(seg.content) }} />
                  </div>
                );
              case 'reasoning':
                return <FoldBlock key={i} title="🧠 思考过程" className="reasoning">{seg.content}</FoldBlock>;
              case 'tool': {
                toolIdxRef.current += 1;
                const loading = isToolActive(seg.content);
                return <FoldBlock key={i} title={`🔧 工具调用 #${toolIdxRef.current}`} loading={loading}>{seg.content}</FoldBlock>;
              }
              case 'subagent': {
                const loading = isSubagentActive(seg.name || '');
                return <FoldBlock key={i} title={`🤖 子 Agent [${seg.name}]`} className="subagent" loading={loading}>{seg.content}</FoldBlock>;
              }
              default:
                return null;
            }
          })}
        </div>
      </div>
    </div>
  );
}, (prev, next) => {
  if (prev.isStreaming !== next.isStreaming) return false;
  if (prev.agentLabel !== next.agentLabel) return false;
  if (prev.activeTools.length !== next.activeTools.length) return false;
  if (prev.activeSubagents.length !== next.activeSubagents.length) return false;
  if (prev.msg.segments.length !== next.msg.segments.length) return false;
  for (let i = 0; i < prev.msg.segments.length; i++) {
    const a = prev.msg.segments[i], b = next.msg.segments[i];
    if (a.type !== b.type || a.content !== b.content || a.name !== b.name) return false;
  }
  return true;
});

const UserMessage = memo(function UserMessage({ msg }: { msg: ChatMessage }) {
  return (
    <div className="chat-block user">
      <div className="chat-meta" style={{ textAlign: 'right' }}>你</div>
      <div className="chat-row user">
        <div className="chat-avatar">👤</div>
        <div className="chat-bubble">
          {msg.attachments && msg.attachments.length > 0 && (
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {msg.attachments.map((a, i) => (
                <span key={i} className="attach-chip">📎 {a.name}</span>
              ))}
            </div>
          )}
          {msg.segments.map((s, i) => (
            <div key={i} style={{ whiteSpace: 'pre-wrap' }}>{s.content}</div>
          ))}
        </div>
      </div>
    </div>
  );
}, (prev, next) => {
  if (prev.msg.segments.length !== next.msg.segments.length) return false;
  for (let i = 0; i < prev.msg.segments.length; i++) {
    if (prev.msg.segments[i].content !== next.msg.segments[i].content) return false;
  }
  const pa = prev.msg.attachments || [], na = next.msg.attachments || [];
  if (pa.length !== na.length) return false;
  for (let i = 0; i < pa.length; i++) {
    if (pa[i].name !== na[i].name) return false;
  }
  return true;
});

// ============ 确认弹窗 ============
function ConfirmDialog({ pending, onDecide, onClose }: {
  pending: PendingConfirm;
  onDecide: (action: 'once' | 'turn' | 'always' | 'deny') => void;
  onClose: () => void;
}) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>🔐 工具执行确认</h3>
        <div className="hint" style={{ marginBottom: 8 }}>
          AI 请求执行以下写/执行操作（读操作不会打扰你）。请选择放行方式：
        </div>
        {pending.tools.map((t, i) => (
          <div key={i} className="tool-card">
            <div className="tool-name">{t.name}</div>
            <pre>{t.input}</pre>
          </div>
        ))}
        <div className="modal-actions">
          <button className="btn primary" onClick={() => onDecide('once')}>✅ 允许这一次</button>
          <button className="btn" onClick={() => onDecide('turn')}>🔄 本回合允许</button>
          <button className="btn" onClick={() => onDecide('always')}>♾️ 永久允许</button>
          <button className="btn danger" onClick={() => onDecide('deny')}>🚫 拒绝</button>
        </div>
        <div className="modal-hint">
          · 允许这一次：仅当前这次调用
          · 本回合允许：本次对话中同类操作不再询问
          · 永久允许：本工作区以后都不再询问（可在会话侧栏「🔐 授权」撤销）
        </div>
      </div>
    </div>
  );
}

// ============ 主页面 ============
export default function ChatPage() {
  const { workspaceId } = useParams();
  const navigate = useNavigate();
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [sessions, setSessions] = useState<SessionItem[]>([]);
  const [sessionId, setSessionId] = useState('');
  const [input, setInput] = useState('');
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [tab, setTab] = useState<'sessions' | 'auth' | 'files'>('sessions');
  const [statusHint, setStatusHint] = useState('');
  const [authList, setAuthList] = useState<{ id: number; toolName: string; createdAt: string }[]>([]);
  const [authOptions, setAuthOptions] = useState<{ name: string; displayName: string }[]>([]);
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [filePath, setFilePath] = useState('');
  interface OpenFile { entry: FileEntry; kind: 'text' | 'image'; content?: string; error?: string; loading: boolean; }
  const [openFiles, setOpenFiles] = useState<OpenFile[]>([]);
  const [activeFileTab, setActiveFileTab] = useState<string | null>(null);
  const [panelWidth, setPanelWidth] = useState(320);
  const [resizing, setResizing] = useState(false);
  const [skillName, setSkillName] = useState<string>('');
  const [availableSkills, setAvailableSkills] = useState<{ name: string; description: string; scope: string }[]>([]);
  const scrollRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const stickRef = useRef(true);
  // 区分"页面首次刷新/首次 mount"与"路由切换返回"：
  // 首次时 store 是空的，但 WS 可能已连上推了 running=true，导致 workspace effect 跳过 loadHistory
  // （因为 existing.running === true），结果只剩 WS 增量事件不完整。
  // 用此标记强制首次 mount 时一定调 loadHistory 拉全量历史。
  const freshLoadRef = useRef(true);

  // 全局聊天状态（切换菜单/路由后保持，流循环写入不依赖组件生命周期）
  const key = chatKey(workspaceId || '', sessionId);
  const chat = useChatSession(key);
  const { messages, running, pending, error, activeTools, activeSubagents } = chat;
  const patch = useCallback((p: Partial<typeof chat>) => {
    updateChatSession(key, (c) => ({ ...c, ...p }));
  }, [key]);
  const patchMsgs = useCallback((fn: (prev: ChatMessage[]) => ChatMessage[]) => {
    updateChatSession(key, (c) => ({ ...c, messages: fn(c.messages) }));
  }, [key]);

  // 批量应用流式事件（一次 store 更新处理多个 delta，减少重渲染频率）
  const applyEvents = useCallback((events: StreamEvent[]) => {
    if (events.length === 0) return;
    patchMsgs((prev) => {
      let next = prev;
      for (const e of events) next = reduceMessage(next, e);
      return next;
    });
  }, [patchMsgs]);

  // ===== 全局 WebSocket =====
  const sessionIdRef = useRef(sessionId);
  const workspaceIdRef = useRef(workspaceId);
  useEffect(() => { sessionIdRef.current = sessionId; }, [sessionId]);
  useEffect(() => { workspaceIdRef.current = workspaceId; }, [workspaceId]);

  // 会话/工作区变化：向服务端注册 sessionId → workspaceId 映射
  // 即使还没发消息也注册，确保后端能正确包装事件的 workspaceId
  useEffect(() => {
    if (sessionId && workspaceId) {
      getChatSocket().send({ type: 'register', workspaceId, sessionId });
    }
  }, [sessionId, workspaceId]);
  const lastEventAtRef = useRef(Date.now());
  const hangTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const pendingEvtsRef = useRef<StreamEvent[]>([]);
  const flushTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const flushNow = useCallback(() => {
    if (flushTimerRef.current) {
      clearTimeout(flushTimerRef.current);
      flushTimerRef.current = null;
    }
    const pending = pendingEvtsRef.current;
    if (pending.length > 0) {
      pendingEvtsRef.current = [];
      applyEvents(pending);
    }
  }, [applyEvents]);

  const scheduleFlush = useCallback(() => {
    if (flushTimerRef.current) return;
    flushTimerRef.current = setTimeout(() => {
      flushTimerRef.current = null;
      flushNow();
    }, 32);
  }, [flushNow]);

  const handleWsEvent = useCallback((evt: StreamEvent) => {
    lastEventAtRef.current = Date.now();
    // 刷新/重连后 running=false，但后端 Agent 可能仍在运行 —— 收到数据事件即自动复位
    if (DATA_EVENT_TYPES.has(evt.type)) {
      const cur = getChatSession(key);
      if (!cur.running) {
        patch({ running: true, error: '' });
      }
    }
    if (evt.type === 'tool') {
      // 工具开始执行：加入活跃集合
      updateChatSession(key, (c) => ({
        ...c,
        activeTools: c.activeTools.includes(evt.content) ? c.activeTools : [...c.activeTools, evt.content],
      }));
      flushNow();
      pendingEvtsRef.current.push(evt);
      scheduleFlush();
    } else if (evt.type === 'tool_end') {
      // 工具执行结束：从活跃集合移除
      updateChatSession(key, (c) => ({
        ...c,
        activeTools: c.activeTools.filter((t: string) => t !== evt.content),
      }));
    } else if (evt.type === 'subagent') {
      // 子 Agent 开始：加入活跃集合
      updateChatSession(key, (c) => ({
        ...c,
        activeSubagents: c.activeSubagents.includes(evt.content) ? c.activeSubagents : [...c.activeSubagents, evt.content],
      }));
      flushNow();
      pendingEvtsRef.current.push(evt);
      scheduleFlush();
    } else if (evt.type === 'subagent_end') {
      // 子 Agent 结束：从活跃集合移除
      updateChatSession(key, (c) => ({
        ...c,
        activeSubagents: c.activeSubagents.filter((s: string) => s !== evt.content),
      }));
    } else if (evt.type === 'text') {
      // text 增量走统一批量渲染（32ms 节流，比打字机快且不丢帧）
      pendingEvtsRef.current.push(evt);
      scheduleFlush();
    } else if (evt.type === 'confirm') {
      flushNow();
      patch({ running: false }); // Agent 已暂停等待确认，复位"输出中"状态
      console.log('[ws-confirm] 收到确认请求:', evt.content);
      try {
        const json = JSON.parse(evt.content);
        const tools = (json.tools || []).map((t: any) => ({ name: t.name, input: JSON.stringify(t.input || {}) }));
        patch({ pending: { tools, raw: evt.content } });
        console.log('[ws-confirm] pending 已设置, 工具数:', tools.length);
      } catch {
        patch({ pending: { tools: [{ name: '工具', input: evt.content }], raw: evt.content } });
      }
    } else if (evt.type === 'error') {
      flushNow();
      // 只显示一次（error-box），不再追加到消息流避免重复
      patch({ error: evt.content });
    } else if (evt.type === 'pending_info') {
      // 轮询兜底：SSE/WS confirm 事件丢失时也能弹窗
      try {
        const json = JSON.parse(evt.content);
        if (json.pending && json.tools && json.tools.length > 0) {
          const wid = workspaceIdRef.current;
          const sid = sessionIdRef.current;
          const cur = wid && sid ? getChatSession(chatKey(wid, sid)) : null;
          if (cur && !cur.pending) {
            patch({
              running: false,
              pending: {
                tools: json.tools.map((t: any) => ({ name: t.name, input: JSON.stringify(t.input || {}) })),
                raw: '',
              },
            });
            console.log('[ws-poll] 轮询到挂起确认:', json.tools.map((t: any) => t.name));
          }
        }
      } catch {
        // 忽略
      }
    } else if (evt.type === 'status') {
      // 后端主动回推的会话状态（register / 重连时触发）
      try {
        const json = JSON.parse(evt.content);
        const cur = getChatSession(key);
        const nextRunning = !!json.running;
        const pendingTools = Array.isArray(json.pendingTools) ? json.pendingTools : [];
        const nextPending = !!json.pending && pendingTools.length > 0;
        const updates: Partial<typeof cur> = {};
        if (cur.running !== nextRunning) updates.running = nextRunning;
        if (nextPending) {
          updates.pending = {
            tools: pendingTools.map((t: any) => ({ name: t.name, input: t.input || '{}' })),
            raw: '',
          };
        } else if (cur.pending && !nextPending) {
          updates.pending = null;
        }
        if (Object.keys(updates).length > 0) {
          patch(updates as any);
          console.log('[ws-status] 会话状态同步:', updates);
        }
      } catch {
        // 忽略格式错误
      }
    } else if (evt.type === 'reconnected') {
      const sid = sessionIdRef.current;
      const wid = workspaceIdRef.current;
      if (sid && wid) {
        getChatSocket().send({ type: 'register', workspaceId: wid, sessionId: sid });
        // 重连后始终拉一次全量历史：确保断线期间可能丢失的 WS 消息补回来
        // （loadHistory 内部会保留 running 状态，不会被覆盖）
        loadHistory(wid, sid);
        setStatusHint('');
        console.log('[ws] 重连成功，已重新注册会话:', sid);
      }
    } else if (evt.type === 'disconnected') {
      setStatusHint('⚠️ 连接断开，正在重连...');
      console.log('[ws] 连接断开，等待重连');
    } else if (evt.type === 'end') {
      flushNow();
      patch({ running: false, activeTools: [], activeSubagents: [] });
      if (hangTimerRef.current) {
        clearInterval(hangTimerRef.current);
        hangTimerRef.current = null;
      }
      // 队列自动发送（介入插队到队首的消息也会被 sendNextQueued 取到）
      setTimeout(() => sendNextQueued(), 0);
    } else {
      // reasoning/tool_args/tool_result/subagent_text 等：统一批量渲染
      pendingEvtsRef.current.push(evt);
      scheduleFlush();
    }
  }, [applyEvents, flushNow, key, patch, scheduleFlush]);

  const handleWsEventRef = useRef(handleWsEvent);
  useEffect(() => {
    handleWsEventRef.current = handleWsEvent;
  }, [handleWsEvent]);

  // 订阅全局连接事件
  // 过滤策略：sid 是唯一的会话标识（同一 WS 连接广播所有会话事件），
  // wid 仅做二次校验。sid 为空表示连接级事件（error/end/reconnected），所有页面都处理。
  useEffect(() => {
    return subscribeChatSocket((wid, sid, evt) => {
      if (!sid) {
        handleWsEventRef.current(evt);
        return;
      }
      // 数据事件：优先靠 sid 匹配，wid 做附加校验（wid 为空时视为可信）
      const sidMatch = sid === sessionIdRef.current;
      const widOk = !wid || wid === workspaceIdRef.current;
      if (sidMatch && widOk) {
        handleWsEventRef.current(evt);
      }
    });
  }, []);

  // 轮询兜底：挂起确认查询走 WS（{type:'pending'}），每 8 秒一次。
  // 注意：Agent 正在流式输出（running=true）或正在等待确认（pending 存在）时，
  // 不需要轮询——流式事件会自己推 confirm 弹窗。只有"空闲期"才兜底查询。
  useEffect(() => {
    if (!workspaceId || !sessionId || running || pending) return;
    const timer = setInterval(() => {
      getChatSocket().send({ type: 'pending', workspaceId, sessionId });
    }, 8000);
    return () => clearInterval(timer);
  }, [workspaceId, sessionId, running, pending]);

  // 加载工作区 + 会话
  useEffect(() => {
    if (!workspaceId) return;
    (async () => {
      try {
        const list = await getJson<Workspace[]>('/api/workspaces');
        const ws = list.find((w) => w.workspaceId === workspaceId);
        if (!ws) {
          navigate('/workspaces');
          return;
        }
        setWorkspace(ws);
        document.title = `${ws.name} · Easy-Claw`;
        const ss = await getJson<SessionItem[]>(`/api/workspaces/${workspaceId}/sessions`);
        setSessions(ss);
        if (ss.length > 0) {
          // 恢复该 workspace 上次活跃的会话（切回来不重置到第一个）；无记录则用第一个
          const lastSid = getActiveSession(workspaceId);
          const targetSid = lastSid && ss.some((s) => s.id === lastSid) ? lastSid : ss[0].id;
          setSessionId(targetSid);
          setActiveSession(workspaceId, targetSid);
          // 首次 mount（freshLoadRef=true）：强制 loadHistory 拉全量历史，
          // 不管 store 里是否已有 running=true（可能是 WS 刚连上推的状态，消息体还没到）。
          // 路由切换返回（freshLoadRef=false）：store 可能已有完整历史或正在进行的流，跳过重载。
          const existing = getChatSession(chatKey(workspaceId, targetSid));
          if (freshLoadRef.current || !(existing.messages.length > 0 || existing.running)) {
            freshLoadRef.current = false;
            loadHistory(workspaceId, targetSid);
          } else {
            // store 已有历史：仍然同步一下 running/pending 状态（HTTP 兜底）
            syncSessionStatus(workspaceId, targetSid);
          }
        } else {
          const created = await postJson<SessionItem>(`/api/workspaces/${workspaceId}/sessions`, { title: '新会话' });
          setSessions([created]);
          setSessionId(created.id);
        }
        loadAuth(workspaceId);
        loadFiles(workspaceId, '');
        loadSkills(workspaceId);
      } catch (e) {
        patch({ error: String(e) });
      }
    })();
  }, [workspaceId]);

  const loadHistory = async (wid: string, sid: string) => {
    try {
      const box = await getJson<BoxMessage[]>(`/api/chat/history?workspaceId=${wid}&sessionId=${sid}`);
      const msgs: ChatMessage[] = [];
      let cur: ChatMessage | null = null;
      for (const b of box) {
        if (b.type === 'USER') {
          cur = null;
          msgs.push({ role: 'user', segments: [{ type: 'text', content: b.content }], attachments: (b.images || []).map((src) => ({ name: 'image', mimeType: 'image/png' })) });
        } else if (b.type === 'AI_TEXT' || b.type === 'THINKING' || b.type === 'TOOL_CALL' || b.type === 'SUBAGENT') {
          if (!cur) {
            cur = { role: 'ai', segments: [] };
            msgs.push(cur);
          }
          if (b.type === 'AI_TEXT') cur.segments.push({ type: 'text', content: b.content });
          else if (b.type === 'THINKING') cur.segments.push({ type: 'reasoning', content: b.content });
          else if (b.type === 'TOOL_CALL') {
            const lastTool = cur.segments[cur.segments.length - 1];
            if (lastTool && lastTool.type === 'tool') {
              lastTool.content += `\n\n── ${b.toolName} ──\n参数: ${b.toolArgs}`;
            } else {
              cur.segments.push({ type: 'tool', content: `── ${b.toolName} ──\n参数: ${b.toolArgs}` });
            }
          } else {
            cur.segments.push({ type: 'subagent', name: b.subagentName || '', content: b.content });
          }
        }
      }
      updateChatSession(chatKey(wid, sid), (c) => ({ ...c, messages: msgs }));
      // 历史加载完后同步运行状态（HTTP 接口兜底，WS status 事件可能稍后到达）
      syncSessionStatus(wid, sid);
    } catch (e) {
      patch({ error: String(e) });
    }
  };

  const syncSessionStatus = async (wid: string, sid: string) => {
    try {
      const status = await getJson<{ running: boolean; pending: boolean; pendingTools: { name: string; input: string }[] }>(
        `/api/chat/status?workspaceId=${wid}&sessionId=${sid}`);
      const cur = getChatSession(chatKey(wid, sid));
      const updates: Partial<typeof cur> = {};
      if (cur.running !== status.running) updates.running = status.running;
      if (status.pending && status.pendingTools.length > 0) {
        updates.pending = { tools: status.pendingTools, raw: '' };
      } else if (cur.pending && !status.pending) {
        updates.pending = null;
      }
      if (Object.keys(updates).length > 0) {
        updateChatSession(chatKey(wid, sid), (c) => ({ ...c, ...updates }));
        console.log('[http-status] 会话状态同步:', updates);
      }
    } catch (e) {
      console.warn('[http-status] 查询失败:', e);
    }
  };

  const loadAuth = async (wid: string) => {
    try {
      const [rules, tools] = await Promise.all([
        getJson<{ id: number; toolName: string; createdAt: string }[]>(`/api/workspaces/${wid}/permissions`),
        getJson<{ name: string; displayName: string }[]>('/api/tools/builtin'),
      ]);
      setAuthList(rules);
      setAuthOptions(tools);
    } catch {
      // 忽略
    }
  };

  const loadSkills = async (wid: string) => {
    try {
      const all = await getJson<{ scope: string; name: string; description: string }[]>(
        `/api/skills?workspaceId=${encodeURIComponent(wid)}`);
      // 包含 system/global/workspace 三级
      setAvailableSkills(all.filter(s => s.scope === 'system' || s.scope === 'global' || s.scope === 'workspace'));
    } catch {
      setAvailableSkills([]);
    }
  };

  /** 切换某个工具的白名单状态：已在白名单→移除，不在→加入 */
  const toggleAuth = async (toolName: string) => {
    if (!workspaceId) return;
    const inList = authList.some((r) => r.toolName === toolName);
    if (inList) {
      await del(`/api/workspaces/${workspaceId}/permissions/${encodeURIComponent(toolName)}`);
    } else {
      await postJson(`/api/workspaces/${workspaceId}/permissions/${encodeURIComponent(toolName)}`, {});
    }
    await loadAuth(workspaceId);
  };

  const loadFiles = async (wid: string, path: string) => {
    try {
      setFiles(await getJson(`/api/workspaces/${wid}/files?path=${encodeURIComponent(path)}`));
    } catch {
    }
  };

  const openFileTab = useCallback(async (entry: FileEntry) => {
    if (!workspaceId) return;
    const kind = fileKind(entry);
    if (kind === 'dir' || kind === 'binary') return;
    const already = openFiles.find((f) => f.entry.path === entry.path);
    if (already) {
      setActiveFileTab(entry.path);
      return;
    }
    const newFile: OpenFile = { entry, kind, loading: true };
    setOpenFiles((prev) => [...prev, newFile]);
    setActiveFileTab(entry.path);
    if (kind === 'text') {
      try {
        const res = await fetch(`/api/workspaces/${workspaceId}/file-content?path=${encodeURIComponent(entry.path)}`);
        if (!res.ok) {
          setOpenFiles((prev) => prev.map((f) => f.entry.path === entry.path ? { ...f, loading: false, error: `HTTP ${res.status}` } : f));
          return;
        }
        const text = await res.text();
        setOpenFiles((prev) => prev.map((f) => f.entry.path === entry.path ? { ...f, loading: false, content: text } : f));
      } catch (e) {
        setOpenFiles((prev) => prev.map((f) => f.entry.path === entry.path ? { ...f, loading: false, error: String(e) } : f));
      }
    } else {
      setOpenFiles((prev) => prev.map((f) => f.entry.path === entry.path ? { ...f, loading: false } : f));
    }
  }, [workspaceId, openFiles]);

  const closeFileTab = (path: string) => {
    setOpenFiles((prev) => {
      const idx = prev.findIndex((f) => f.entry.path === path);
      const next = prev.filter((f) => f.entry.path !== path);
      if (activeFileTab === path) {
        const newActive = next[Math.max(0, idx - 1)]?.entry.path ?? next[0]?.entry.path ?? null;
        setActiveFileTab(newActive);
      }
      return next;
    });
  };

  // 自动滚动
  useEffect(() => {
    if (!stickRef.current) return;
    requestAnimationFrame(() => {
      const el = scrollRef.current;
      if (el && stickRef.current) el.scrollTop = el.scrollHeight;
    });
  }, [messages]);

  // 输入框自适应高度
  useEffect(() => {
    const ta = textareaRef.current;
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 160) + 'px';
  }, [input]);

  const switchSession = (sid: string) => {
    setSessionId(sid);
    if (workspaceId) {
      setActiveSession(workspaceId, sid);
      loadHistory(workspaceId, sid);
    }
  };
  const createSession = async () => {
    if (!workspaceId) return;
    const created = await postJson<SessionItem>(`/api/workspaces/${workspaceId}/sessions`, { title: `会话 ${sessions.length + 1}` });
    setSessions([created, ...sessions]);
    setSessionId(created.id);
  };

  const deleteSession = async (sid: string) => {
    if (!workspaceId || !confirm('删除该会话？')) return;
    await del(`/api/workspaces/${workspaceId}/sessions/${sid}`);
    const rest = sessions.filter((s) => s.id !== sid);
    setSessions(rest);
    if (sid === sessionId) {
      if (rest.length > 0) switchSession(rest[0].id);
      else patchMsgs(() => []);
    }
  };

  // 附件：选择文件
  const pickFiles = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files || []);
    files.forEach((f) => {
      if (f.size > 8 * 1024 * 1024) {
        alert(`附件「${f.name}」超过 8MB 限制，请压缩后再试`);
        return;
      }
      const reader = new FileReader();
      reader.onload = () => {
        const b64 = String(reader.result).split(',')[1];
        setAttachments((prev) => [...prev, { name: f.name, mimeType: f.type || 'application/octet-stream', base64Data: b64 }]);
      };
      reader.readAsDataURL(f);
    });
    e.target.value = '';
  };

  // 附件：粘贴截图
  const onPaste = (e: React.ClipboardEvent) => {
    const items = e.clipboardData?.items;
    if (!items) return;
    for (const item of items) {
      if (item.type.startsWith('image/')) {
        const file = item.getAsFile();
        if (!file) continue;
        if (file.size > 8 * 1024 * 1024) {
          alert('截图超过 8MB 限制，请压缩后再试');
          return;
        }
        const reader = new FileReader();
        reader.onload = () => {
          const b64 = String(reader.result).split(',')[1];
          setAttachments((prev) => [...prev, { name: file.name || 'pasted-image.png', mimeType: item.type, base64Data: b64 }]);
        };
        reader.readAsDataURL(file);
        break;
      }
    }
  };

  // 真正发送一条消息到后端（内部函数，不处理队列）
  const sendNow = useCallback((text: string, myAtts: AttachmentPayload[]) => {
    if (!workspaceId || !sessionId) return;
    patchMsgs((prev) => [
      ...prev,
      { role: 'user', segments: [{ type: 'text', content: text }], attachments: myAtts.map((a) => ({ name: a.name, mimeType: a.mimeType })) },
    ]);
    patch({ running: true, error: '', pending: null }); // 新对话开始：关闭可能残留的确认弹窗
    setStatusHint('');
    lastEventAtRef.current = Date.now();
    if (hangTimerRef.current) clearInterval(hangTimerRef.current);
    hangTimerRef.current = setInterval(() => {
      if (Date.now() - lastEventAtRef.current > 60000) {
        setStatusHint('⚠️ 长时间无响应：AI 可能正在等待确认（若弹窗未出现请刷新页面重试）或网络异常');
        if (hangTimerRef.current) clearInterval(hangTimerRef.current);
      }
    }, 5000);
    const chatMsg: Record<string, unknown> = {
      type: 'chat',
      workspaceId,
      sessionId,
      message: text,
      attachments: myAtts,
      skillName: skillName || null,
    };
    console.log('[ws-send] chat:', { workspaceId, sessionId, msgLen: text.length, atts: myAtts.length, skillName });
    getChatSocket().send(chatMsg);
  }, [workspaceId, sessionId, patch, patchMsgs, skillName]);

  // 发送队列中的下一条
  const sendNextQueued = useCallback(() => {
    const cur = getChatSession(key);
    if (cur.running || cur.messageQueue.length === 0) return;
    const next = cur.messageQueue[0];
    updateChatSession(key, (c) => ({ ...c, messageQueue: c.messageQueue.slice(1) }));
    sendNow(next.text, next.attachments);
  }, [key, sendNow]);

  // 介入当前输入：插队到队首，等 LLM 自然 end 后优先发送
  const interveneNow = useCallback(() => {
    if (!workspaceId || !sessionId || !running) return;
    const text = input.trim();
    if (!text && attachments.length === 0) return;
    const item: QueueItem = {
      id: `q-intervene-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      text,
      attachments: [...attachments],
      guides: [],
    };
    setInput('');
    setAttachments([]);
    updateChatSession(key, (c) => ({ ...c, messageQueue: [item, ...c.messageQueue] }));
    console.log('[intervene] 介入已入队首:', { textLen: text.length, queueAfter: 1 + getChatSession(key).messageQueue.length });
  }, [workspaceId, sessionId, running, input, attachments, key, updateChatSession]);

  // 将指定队列项提到队首（等 LLM 自然 end 后优先发送）
  const interveneQueueItem = useCallback((index: number) => {
    const cur = getChatSession(key);
    if (cur.messageQueue.length === 0 || index < 0 || index >= cur.messageQueue.length) return;
    const target = cur.messageQueue[index];
    const newQueue = [target, ...cur.messageQueue.filter((_, i) => i !== index)];
    updateChatSession(key, (c) => ({ ...c, messageQueue: newQueue }));
    console.log('[intervene] 队列项 idx=', index, '提到队首');
  }, [key, workspaceId, sessionId]);

  // 给当前正在运行的对话追加引导消息（入队带 guides）
  const submitWithGuides = useCallback((text: string, guides: string[]) => {
    if (!workspaceId || !sessionId) return;
    const myAtts = [...attachments];
    setAttachments([]);
    const item: QueueItem = {
      id: `q-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      text,
      attachments: myAtts,
      guides,
    };
    if (running) {
      updateChatSession(key, (c) => ({ ...c, messageQueue: [...c.messageQueue, item] }));
      console.log('[queue] 已入队:', { textLen: text.length, guides });
    } else {
      // 非 running 时直接发送（不进队列）
      setInput('');
      sendNow(text, myAtts);
    }
  }, [workspaceId, sessionId, attachments, running, key, sendNow]);

  // 发送（非 running → 直接发送；running → 入队；Ctrl/Cmd 运行中可加引导）
  const submit = async () => {
    if (!workspaceId || !sessionId) return;
    const text = input.trim();
    if (!text && attachments.length === 0) return;
    if (running) {
      // running 时点击发送 → 入队（后续自动发送）
      const myAtts = [...attachments];
      setAttachments([]);
      const item: QueueItem = {
        id: `q-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
        text,
        attachments: myAtts,
        guides: [],
      };
      updateChatSession(key, (c) => ({ ...c, messageQueue: [...c.messageQueue, item] }));
      setInput('');
      console.log('[queue] running → 已入队, qLen=', getChatSession(key).messageQueue.length);
      return;
    }
    setInput('');
    const myAtts = [...attachments];
    setAttachments([]);
    sendNow(text, myAtts);
  };

  const decideConfirm = async (action: 'once' | 'turn' | 'always' | 'deny') => {
    if (!workspaceId || !sessionId || !pending) return;
    const toolNames = pending.tools.map((t) => t.name);
    patch({ pending: null });
    getChatSocket().send({ type: 'confirm', workspaceId, sessionId, toolNames, action });
    if (action === 'always') loadAuth(workspaceId);
  };

  // 侧栏：工具白名单（全量工具 + 开关矩阵，点即加入/移除）
  const authPanel = (
    <div className="session-list" style={{ padding: '4px 8px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h4 style={{ margin: '0 0 8px' }}>🔐 工具白名单 <span className="hint" style={{ fontWeight: 400 }}>（{authList.length}/{authOptions.length}）</span></h4>
        <button className="btn small" onClick={() => workspaceId && loadAuth(workspaceId)}>刷新</button>
      </div>
      {authOptions.length === 0 ? (
        <div className="hint">点击「刷新」加载可用工具列表</div>
      ) : (
        authOptions.map((t) => {
          const on = authList.some((r) => r.toolName === t.name);
          return (
            <div key={t.name} className="session-item" style={{ cursor: 'default' }}>
              <span style={{ flex: 1, fontSize: 13, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={t.name}>
                {t.displayName || t.name}
              </span>
              <label className={`toggle ${on ? 'on' : ''}`} onClick={() => toggleAuth(t.name)}>
                <span className="toggle-thumb" />
              </label>
            </div>
          );
        })
      )}
      <div className="hint" style={{ marginTop: 8, fontSize: 11 }}>
        ✅ 白名单内的工具调用时不再询问确认
      </div>
    </div>
  );

  // 侧栏：文件面板
  const filePanel = (
    <div className="session-list file-panel" style={{ padding: '4px 8px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h4 style={{ margin: '0 0 8px' }}>📁 文件 <span className="hint" style={{ fontWeight: 400 }}>{filePath || '/'}</span></h4>
        <button className="btn small" onClick={() => loadFiles(workspaceId || '', filePath)}>刷新</button>
      </div>
      {filePath && (
        <button
          className="btn small"
          style={{ marginBottom: 6 }}
          onClick={() => {
            const idx = filePath.lastIndexOf('/');
            const parent = idx > 0 ? filePath.slice(0, idx) : '';
            setFilePath(parent);
            loadFiles(workspaceId || '', parent);
          }}
        >
          ⬆ 上级目录
        </button>
      )}
      {files.length === 0 ? (
        <div className="hint">空目录</div>
      ) : (
        <>
          {files.filter(f => f.directory).map((f, i) => (
            <div key={'d' + i} className={`session-item file-entry fc-dir`} style={{ cursor: 'pointer' }}
                 onClick={() => {
                   const p = filePath ? `${filePath}/${f.name}` : f.name;
                   setFilePath(p);
                   loadFiles(workspaceId || '', p);
                 }}
                 title="进入目录">
              <span className="file-entry-icon">📂</span>
              <span className="file-entry-name">{f.name}</span>
            </div>
          ))}
          {files.filter(f => !f.directory).map((f, i) => {
            const kind = fileKind(f);
            const canOpen = kind === 'text' || kind === 'image';
            return (
              <div
                key={'f' + i}
                className={`session-item file-entry ${fileColorClass(f)} ${canOpen ? 'openable' : 'not-openable'}`}
                style={{ cursor: canOpen ? 'pointer' : 'default' }}
                onClick={() => canOpen && openFileTab(f)}
                title={canOpen ? '点击打开预览' : '不支持预览'}
              >
                <span className="file-entry-icon">{fileIcon(f)}</span>
                <span className="file-entry-name">{f.name}</span>
                <span className="file-entry-size">{formatSize(f.size)}</span>
                {canOpen && <span className="file-entry-open">↗</span>}
              </div>
            );
          })}
        </>
      )}
    </div>
  );

  const chatArea = (
    <div className="chat-main">
      <div className="chat-topbar">
        <span className="ws-name">💬 {workspace?.name}</span>
        {workspace?.agentName && <span className="agent-badge">🤖 {workspace.agentName}</span>}
        {running && (
          <span className="status-badge running">
            <span className="spinner-tiny" /> 主 Agent 运行中
          </span>
        )}
        {activeSubagents.length > 0 && activeSubagents.map((s) => (
          <span key={s} className="status-badge subagent">
            <span className="spinner-tiny" /> 🤖 {s}
          </span>
        ))}
        {activeTools.length > 0 && activeTools.map((t) => (
          <span key={t} className="status-badge tool">
            <span className="spinner-tiny" /> 🔧 {t}
          </span>
        ))}
        <span className="ws-path">{workspace?.path}</span>
        <button
            className="btn refresh-env-btn"
            onClick={async () => {
              const btn = document.querySelector('.refresh-env-btn') as HTMLButtonElement;
              if (btn) { btn.disabled = true; btn.textContent = '刷新中...'; }
              try {
                const res = await postJson<{success: boolean; pathSegments?: number; error?: string}>(
                    `/api/workspaces/${workspaceId}/refresh-env`, {});
                if (res.success) {
                  if (btn) { btn.textContent = `✅ 已刷新 (${res.pathSegments} 段 PATH)`; }
                  setTimeout(() => { if (btn) btn.textContent = '🔄 刷新运行环境'; }, 2500);
                } else {
                  if (btn) { btn.textContent = `❌ ${res.error || '刷新失败'}`; }
                  setTimeout(() => { if (btn) btn.textContent = '🔄 刷新运行环境'; }, 3000);
                }
              } catch (e) {
                if (btn) { btn.textContent = `❌ ${String(e)}`; btn.disabled = false; }
                setTimeout(() => { if (btn) { btn.textContent = '🔄 刷新运行环境'; btn.disabled = false; } }, 3000);
              }
            }}
            title="运行中安装/切换语言（node/python/java）后点击，让 execute 感知最新 PATH"
        >🔄 刷新运行环境</button>
      </div>
      <div className="chat-scroll" ref={scrollRef} onScroll={(e) => {
        const el = e.currentTarget;
        stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 150;
      }}>
        <div className="chat-messages">
          {messages.length === 0 && !running && (
            <div className="empty">
              <h3>开始对话</h3>
              <p className="hint">AI 只能在你指定的工作区目录内工作</p>
            </div>
          )}
          {messages.map((m, i) => (
            m.role === 'user'
              ? <UserMessage key={i} msg={m} />
              : <AiMessage key={i} msg={m} isStreaming={running && i === messages.length - 1} agentLabel={workspace?.agentName || workspace?.name} activeTools={activeTools} activeSubagents={activeSubagents} />
          ))}
        </div>
      </div>

      <div className="chat-input-dock">
        <div className="chat-status">
          {running
            ? (statusHint || '🤖 AI 正在输出...')
            : (pending ? '🔐 AI 正在等待你的确认...' : '💬 输入消息开始对话')}
          {chat.messageQueue.length > 0 && (
            <span style={{ marginLeft: 8, color: 'var(--accent)' }}>
              📥 {chat.messageQueue.length} 条待发
            </span>
          )}
        </div>
        {/* 队列展示 */}
        {chat.messageQueue.length > 0 && (
          <div className="queue-bar">
            {chat.messageQueue.map((item, i) => (
              <div key={item.id} className={`queue-item ${i === 0 ? 'next' : ''}`}>
                <span className="queue-num">{i === 0 ? '▶' : i + 1}</span>
                <span className="queue-text" title={item.text}>{item.text}</span>
                {item.guides.length > 0 && <span className="queue-guides" title={item.guides.join(', ')}>🧭 {item.guides.length}</span>}
                {(running || pending) && (
                  <button
                    className="queue-intervene"
                    title="提到队首（等 LLM 输出完成后优先发送）"
                    onClick={() => interveneQueueItem(i)}
                  >⚡插队</button>
                )}
                {i === 0 && running && (
                  <button className="queue-del" onClick={() => updateChatSession(key, (c) => ({ ...c, messageQueue: c.messageQueue.slice(1) }))}>✕</button>
                )}
                {i !== 0 && (
                  <button className="queue-del" onClick={() => updateChatSession(key, (c) => ({ ...c, messageQueue: c.messageQueue.filter((_, j) => j !== i) }))}>✕</button>
                )}
              </div>
            ))}
          </div>
        )}
        {error && <div className="error-box" style={{ margin: '4px 0' }}>{error}</div>}
        {attachments.length > 0 && (
          <div className="attachment-bar">
            {attachments.map((a, i) => (
              <span key={i} className="attach-chip">
                📎 {a.name}
                <button onClick={() => setAttachments((prev) => prev.filter((_, j) => j !== i))}>✕</button>
              </span>
            ))}
          </div>
        )}
        <div className="chat-mode-bar">
          {availableSkills.length > 0 && (
            <>
              <span className="hint" style={{ marginRight: 4 }}>Skill:</span>
              <select
                className="skill-select"
                value={skillName}
                onChange={(e) => setSkillName(e.target.value)}
                title="加载 Skill 作为本轮对话的操作指南"
              >
                <option value="">— 不指定 —</option>
                {availableSkills.map((s) => (
                  <option key={s.scope + ':' + s.name} value={s.name}>
                    {s.name}{s.description ? ` — ${s.description}` : ''}
                  </option>
                ))}
              </select>
            </>
          )}
        </div>
        <div className="input-row">
          <label className="btn small" style={{ alignSelf: 'flex-end', marginBottom: 6 }}>
            📎 附件
            <input type="file" multiple style={{ display: 'none' }} onChange={pickFiles} />
          </label>
          <textarea
            ref={textareaRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onPaste={onPaste}
            onKeyDown={(e) => {
              if (e.nativeEvent.isComposing) return;
              if (e.key === 'Enter' && !e.shiftKey && (e.ctrlKey || e.metaKey) && running) {
                e.preventDefault();
                interveneNow();
              } else if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.metaKey) {
                e.preventDefault();
                submit();
              }
            }}
            placeholder={running
              ? `运行中 — Enter 入队，Ctrl+Enter 插队，Shift+Enter 换行`
              : '输入消息，Enter 发送，Shift+Enter 换行，可粘贴截图'}
            rows={1}
          />
          {running && (
            <button
              className="btn danger"
              style={{ alignSelf: 'flex-end', marginBottom: 6 }}
              onClick={() => {
                getChatSocket().send({ type: 'stop', workspaceId, sessionId });
                console.log('[ws-send] stop');
              }}
              title="停止当前回复"
            >⏹ 停止</button>
          )}
          {running && (input.trim() || attachments.length > 0) && (
            <button
              className="btn intervene-btn"
              style={{ alignSelf: 'flex-end', marginBottom: 6 }}
              onClick={interveneNow}
              title="插队：等 LLM 输出完成后优先发送（Ctrl+Enter）"
            >⚡插队</button>
          )}
          <button className={`btn ${running ? 'primary' : 'primary'}`} style={{ alignSelf: 'flex-end', marginBottom: 6 }} onClick={submit}>
            {running ? '📥 入队' : '➤ 发送'}
          </button>
        </div>
      </div>
    </div>
  );

  // resizer 拖拽
  const onResizerDown = (e: React.MouseEvent) => {
    e.preventDefault();
    setResizing(true);
    const startX = e.clientX;
    const startW = panelWidth;
    const onMove = (ev: MouseEvent) => {
      const delta = startX - ev.clientX;
      const newW = Math.max(240, Math.min(600, startW + delta));
      setPanelWidth(newW);
    };
    const onUp = () => {
      setResizing(false);
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  };

  const activeFile = openFiles.find((f) => f.entry.path === activeFileTab) ?? null;

  const fileContentView = (file: OpenFile) => {
    if (file.loading) return <div className="file-preview-loading"><span className="spinner-tiny" /> 加载中...</div>;
    if (file.error) return <div className="error-box">{file.error}</div>;
    if (file.kind === 'image') {
      return (
        <div className="file-preview-image-wrap">
          <img
            className="file-preview-image"
            src={`/api/workspaces/${workspaceId}/file-content?path=${encodeURIComponent(file.entry.path)}`}
            alt={file.entry.name}
          />
        </div>
      );
    }
    const ext = extOf(file.entry.name);
    const mdContent = (ext === 'md' || ext === 'markdown') ? md(file.content || '') : null;
    if (mdContent) {
      return <div className="file-markdown-body" dangerouslySetInnerHTML={{ __html: mdContent }} />;
    }
    return <pre className="file-preview-text">{file.content}</pre>;
  };

  const renderToolPanel = () => {
    if (openFiles.length > 0) {
      return (
        <div className="tool-panel-inner">
          <div className="file-tab-bar">
            {openFiles.map((f) => (
              <div
                key={f.entry.path}
                className={`file-tab ${f.entry.path === activeFileTab ? 'active' : ''}`}
                onClick={() => setActiveFileTab(f.entry.path)}
                title={f.entry.path}
              >
                <span className="file-tab-icon">{fileIcon(f.entry)}</span>
                <span className="file-tab-name">{f.entry.name}</span>
                <button
                  className="file-tab-close"
                  onClick={(e) => { e.stopPropagation(); closeFileTab(f.entry.path); }}
                  title="关闭"
                >✕</button>
              </div>
            ))}
          </div>
          <div className="file-tab-content">
            {activeFile ? fileContentView(activeFile) : <div className="hint" style={{ padding: 16 }}>无内容</div>}
          </div>
        </div>
      );
    }
    switch (tab) {
      case 'sessions':
        return (
          <>
            <button className="btn primary" style={{ width: '100%', marginBottom: 8 }} onClick={createSession}>＋ 新会话</button>
            <div className="session-list">
              {sessions.length === 0 && <div className="hint">暂无会话</div>}
              {sessions.map((s) => (
                <div
                  key={s.id}
                  className={`session-item ${s.id === sessionId ? 'active' : ''}`}
                  onClick={() => switchSession(s.id)}
                >
                  <span className="title">{s.title}</span>
                  <button
                    className="session-del"
                    onClick={(e) => { e.stopPropagation(); deleteSession(s.id); }}
                  >🗑</button>
                </div>
              ))}
            </div>
          </>
        );
      case 'auth':
        return authPanel;
      case 'files':
        return filePanel;
    }
  };

  return (
    <div className={`chat-page ${resizing ? 'resizing' : ''}`}>
      {chatArea}

      <div className="chat-resizer" onMouseDown={onResizerDown} title="拖拽调整" />

      <div className="chat-side" style={{ width: panelWidth }}>
        <div className="side-tabs">
          <button className={`btn small ${tab === 'sessions' ? 'primary' : ''}`} onClick={() => setTab('sessions')}>💬 会话</button>
          <button className={`btn small ${tab === 'auth' ? 'primary' : ''}`} onClick={() => setTab('auth')}>🔐 授权</button>
          <button className={`btn small ${tab === 'files' ? 'primary' : ''}`} onClick={() => setTab('files')}>📁 文件</button>
          {openFiles.length > 0 && (
            <button className="btn small" onClick={() => { setOpenFiles([]); setActiveFileTab(null); }} title="关闭所有文件">📑 ×</button>
          )}
        </div>
        <div className="side-body">
          {renderToolPanel()}
        </div>
      </div>

      {pending && (
        <ConfirmDialog
          pending={pending}
          onDecide={decideConfirm}
          onClose={() => decideConfirm('deny')}
        />
      )}
    </div>
  );
}
