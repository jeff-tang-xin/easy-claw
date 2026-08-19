import {memo, useCallback, useEffect, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {del, getJson, postJson, type StreamEvent} from '../api';
import {marked} from 'marked';
import DOMPurify from 'dompurify';
import type {AgentGroup, AttachmentPayload, ChatMessage, ExecStatus, ExecutionLogEntry, LlmEntry, PendingConfirm, PlanState, PlanStep, QueueItem, ToolEntry, ActionEntry, SubagentEntry, PlanValidation} from '../chatStore';
import {
    chatKey,
    getActiveSession,
    getChatSession,
    groupByProcess,
    newMessageId,
    setActiveSession,
    updateChatSession,
    useChatSession
} from '../chatStore';
import {getChatSocket, subscribeChatSocket} from '../chatSocket';
import {PlanProgressBar} from '../components/PlanProgressBar';
import {ExecutionStream} from '../components/ExecutionStream';
import {LiveActions} from '../components/LiveActions';

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

/**
 * 错误信息友好化：把技术错误翻译成用户能懂的话
 * 返回 { friendly: 友好文案, isTransformed: 是否做了转换 }
 */
function friendlyError(error: string): string {
  if (!error) return '';
  const e = error.toLowerCase();
  if (e.includes('network') || e.includes('failed to fetch') || e.includes('连接')) {
    return '🌐 网络连接失败，请检查网络后重试';
  }
  if (e.includes('timeout') || e.includes('超时')) {
    return '⏰ 请求超时，请稍后再试';
  }
  if (e.includes('401') || e.includes('403') || e.includes('unauthorized') || e.includes('权限')) {
    return '🔐 权限不足，请检查设置后重试';
  }
  if (e.includes('404') || e.includes('not found')) {
    return '🔍 资源不存在，可能已被删除或路径错误';
  }
  if (e.includes('500') || e.includes('internal server error') || e.includes('nullpointer') || e.includes('exception')) {
    return '⚙️ 服务端出现了一点小问题，请稍后再试';
  }
  if (e.includes('rate limit') || e.includes('限流') || e.includes('429')) {
    return '⏳ 请求太频繁了，请稍等片刻再试';
  }
  return error;
}

const mdCache = new Map<string, string>();
const mdCacheMax = 256;

// ============ 事件路由表 ============
// 明确每个事件类型应该去往哪个视图
// - chat:    仅进入聊天消息列表（reduceMessage 处理）
// - exec:    仅进入执行流瀑布流（execEvents 收集）
// - both:    两个视图都要
// - control: 仅控制逻辑处理，不进入任何视图
const EVENT_ROUTE: Record<string, 'chat' | 'exec' | 'both' | 'control'> = {
  // 对话输出 → 两个视图都要
  text: 'both',
  reasoning: 'both',
  // 工具调用 → 聊天视图 + 执行流
  tool: 'chat',
  tool_args: 'chat',
  tool_result: 'chat',
  // 子 Agent → 聊天视图（简化显示）
  subagent: 'chat',
  subagent_text: 'chat',
  // 执行流专属事件（瀑布流展示）
  plan: 'exec',
  step: 'exec',
  tool_call: 'exec',
  llm_call: 'exec',
  agent_action: 'exec',
  subagent_lifecycle: 'exec',
  llm_thinking: 'exec',
  progress_update: 'exec',
  goal_achieved: 'exec',
  tool_loop: 'exec',
  state_transition: 'exec',
  replan_requested: 'exec',
  llm_invocation: 'exec',
  // 控制事件
  confirm: 'control',
  error: 'control',
  pending_info: 'control',
  tool_end: 'control',
  subagent_end: 'control',
  subagent_failed: 'control',
};

// 这些事件说明 Agent 正在输出，可用于重连后自动恢复 running 状态
const DATA_EVENT_TYPES = new Set([
  ...Object.keys(EVENT_ROUTE).filter(k => EVENT_ROUTE[k] !== 'control'),
]);
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
        next.push({ id: newMessageId(), role: 'ai', segments: [{ type: 'text', content: evt.content }] });
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
        next.push({ id: newMessageId(), role: 'ai', segments: [{ type: 'reasoning', content: evt.content }] });
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
        next.push({ id: newMessageId(), role: 'ai', segments: [{ type: 'tool', content: `── ${evt.content} ──` }] });
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
        next.push({ id: newMessageId(), role: 'ai', segments: [{ type: 'subagent', name: evt.content, content: '' }] });
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

const AiMessage = memo(function AiMessage({ msg, isStreaming, agentLabel, running }: {
  msg: ChatMessage; isStreaming?: boolean; agentLabel?: string; running?: boolean;
}) {
  const label = agentLabel || 'AI';
  const hasText = msg.segments.some(s => s.type === 'text' && s.content.trim());
  const hasFlow = !!msg.plan || (!!msg.executionLog && msg.executionLog.length > 0);
  const hasRunningEntries = running && msg.executionLog && msg.executionLog.some(e => e.status === 'running');

  return (
    <div className="chat-block ai">
      <div className="chat-row">
        <div className="chat-avatar">🤖</div>
        <div className="chat-bubble-wrap">
          <div className="chat-bubble-header">
            <span className="chat-bubble-name">{label}</span>
            {running && <span className="chat-bubble-status"><span className="spinner-tiny" />执行中</span>}
          </div>
          <div className="chat-bubble">
            {/* 🔴 醒目的 Plan 进度条 */}
              <details
                className="plan-details"
                open={running || (msg.plan?.steps?.some(s => s.status === 'running' || s.status === 'pending') ?? false)}
              >
                <summary>🎯 执行计划</summary>
            {msg.plan && msg.plan.steps.length > 0 && (
              <PlanProgressBar plan={msg.plan} />
            )}

            {/* 🔴 实时动作反馈（运行中显示，消除黑盒感） */}
            {running && msg.executionLog && msg.executionLog.length > 0 && (
              <LiveActions entries={msg.executionLog!} running={running} />
            )}

            {/* 详细执行日志（默认折叠，用户主动展开查看） */}
            {hasFlow && (
              <details className="exec-details">
                <summary className="exec-details-summary">
                  🔍 查看详细执行日志
                  <span className="exec-details-stats">
                    {msg.executionLog?.length || 0} 条记录
                  </span>
                </summary>
                <AgentPanels plan={msg.plan} entries={msg.executionLog || []} running={!!running} />
              </details>
            )}

              {/* 🧠 LLM 思考过程 */}
              {msg.segments.filter(s => s.type === 'reasoning' && s.content.trim()).map((seg, i) => (
                <>

                <details key={i} className="reasoning-block" open={isStreaming}>
                  <summary>🧠 LLM 思考过程</summary>
                  <div className="reasoning-content">{seg.content}</div>
                </details>

              {/* 🤖 子 Agent 输出内容 */}
              {msg.segments.filter(s => s.type === 'subagent' && s.content.trim()).map((seg, i) => (
                <div key={i} className="subagent-output">
                  <div className="subagent-output-title">🤖 {seg.name || '子 Agent'}</div>
                  <div className="subagent-output-content">{seg.content}</div>
                </div>
              ))}
                </>

              ))}

            {msg.segments.filter(s => s.type === 'text').map((seg, i) => (
              isStreaming ? (
                <div key={i} className="md-content-wrap">
                  <div className="md-content" style={{ whiteSpace: 'pre-wrap' }}>{seg.content}</div>
                  {i === msg.segments.filter(s => s.type === 'text').length - 1 ? <span className="typing-cursor" /> : null}
                </div>
              ) : (
                <div key={i} className="md-content-wrap">
                  <div className="md-content" dangerouslySetInnerHTML={{ __html: md(seg.content) }} />
                </div>
              )
            ))}
            {/* 没有任何内容也没有执行信息时，才显示极简思考态 */}
            {!hasFlow && !hasText && (
              <div className="ai-thinking">🧠 思考中…</div>
            )}
            </details>

          </div>
        </div>
      </div>
    </div>
  );
}, (prev, next) => {
  if (prev.isStreaming !== next.isStreaming) return false;
  if (prev.agentLabel !== next.agentLabel) return false;
  if (prev.running !== next.running) return false;
  if (prev.msg.segments.length !== next.msg.segments.length) return false;
  for (let i = 0; i < prev.msg.segments.length; i++) {
    const a = prev.msg.segments[i], b = next.msg.segments[i];
    if (a.type !== b.type || a.content !== b.content) return false;
  }
  // executionLog 长度或内容变化（tool 从 running→done 等）都要重渲染
  const prevLog = prev.msg.executionLog;
  const nextLog = next.msg.executionLog;
  if ((prevLog?.length || 0) !== (nextLog?.length || 0)) return false;
  if (prevLog && nextLog) {
    for (let i = 0; i < prevLog.length; i++) {
      if (prevLog[i].status !== nextLog[i].status) return false;
      // tool 名称变化也要重渲染（running 时 tool 名可能尚未可知）
      if ('tool' in prevLog[i] && 'tool' in nextLog[i] && (prevLog[i] as any).tool !== (nextLog[i] as any).tool) return false;
    }
  }
  // plan 步骤变化
  const prevPlan = prev.msg.plan;
  const nextPlan = next.msg.plan;
  if ((prevPlan?.steps.length || 0) !== (nextPlan?.steps.length || 0)) return false;
  if (prevPlan && nextPlan) {
    for (let i = 0; i < prevPlan.steps.length; i++) {
      if (prevPlan.steps[i].status !== nextPlan.steps[i].status) return false;
      if (prevPlan.steps[i].name !== nextPlan.steps[i].name) return false;
    }
    if (prevPlan.goal !== nextPlan.goal) return false;
  }
  return true;
});

// ==================== 执行时间线（合并 Plan + ExecutionLog） ====================

function formatDuration(ms?: number): string {
  if (ms == null) return '';
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${ms}ms`;
}

function statusIcon(s: string): string {
  if (s === 'running') return '◉';
  if (s === 'done') return '✓';
  if (s === 'failed') return '✕';
  return '·';
}

function statusText(s: string): string {
  if (s === 'running') return '运行中';
  if (s === 'done') return '完成';
  if (s === 'failed') return '失败';
  return '待执行';
}

function AgentPanels({ entries, plan, running }: { entries: ExecutionLogEntry[]; plan?: PlanState; running?: boolean }) {
  const groups = groupByProcess(entries);
  const [openAgents, setOpenAgents] = useState<Set<string>>(() => new Set(
    groups.filter(g => g.status === 'running').map(g => g.processId)
  ));

  if (groups.length === 0) return null;

  const totalLlm = entries.filter(e => e.kind === 'llm').length;
  const totalTool = entries.filter(e => e.kind === 'tool').length;
  const runningCount = groups.filter(g => g.status === 'running').length;
  const totalDuration = groups.reduce((sum, g) => {
    if (g.endedAt && g.startedAt) return sum + (g.endedAt - g.startedAt);
    if (g.startedAt) return sum + (Date.now() - g.startedAt);
    return sum;
  }, 0);

  const toggleAgent = (pid: string) => {
    setOpenAgents(prev => {
      const next = new Set(prev);
      if (next.has(pid)) next.delete(pid); else next.add(pid);
      return next;
    });
  };

  return (
    <div className={`exec-timeline ${running ? 'running' : ''}`}>
      {(plan?.goal || totalLlm > 0 || totalTool > 0) && (
        <div className="exec-timeline-summary">
          {plan?.goal && <span className="exec-timeline-goal">🎯 {plan.goal}</span>}
          <span className="exec-timeline-stats">
            🤖{groups.length}
            {runningCount > 0 && <span className="exec-live-dot">● 运行中 {runningCount}</span>}
            {totalLlm > 0 && <span>🧠{totalLlm}</span>}
            {totalTool > 0 && <span>🔧{totalTool}</span>}
            {totalDuration > 0 && <span className="exec-timeline-cost">{formatDuration(totalDuration)}</span>}
          </span>
        </div>
      )}
      <div className="exec-timeline-list">
        {groups.map((g) => (
            <div key={g.processId} className={g.parentProcessId || g.agentName?.startsWith('subagent_') ? 'exec-agent-child' : 'exec-agent-root'}>
          <AgentPanel
            key={g.processId}
            group={g}
            open={openAgents.has(g.processId) || g.status === 'running'}
            onToggle={() => toggleAgent(g.processId)}
          />
            </div>
        ))}
      </div>
    </div>
  );
}

function AgentPanel({ group, open, onToggle }: { group: AgentGroup; open: boolean; onToggle: () => void }) {
  const llmEntries = group.entries.filter(e => e.kind === 'llm') as LlmEntry[];
  const toolEntries = group.entries.filter(e => e.kind === 'tool') as ToolEntry[];
  const actionEntries = group.entries.filter(e => e.kind === 'action') as ActionEntry[];
  const runningEntryCount = group.entries.filter(e => e.status === 'running').length;
  const durationMs = group.endedAt && group.startedAt ? group.endedAt - group.startedAt : undefined;

  return (
    <div className={`exec-agent ${group.status}`}>
      <div className="exec-agent-head" onClick={onToggle}>
        <span className={`exec-agent-dot ${group.status}`}>{statusIcon(group.status)}</span>
        <span className="exec-agent-name">🤖 {group.agentName}</span>
        {group.actionName && group.actionName !== group.agentName && (
          <span className="exec-agent-action">· {group.actionName}</span>
        )}
        <span className="exec-agent-stats">
          {llmEntries.length > 0 && <span>🧠{llmEntries.length}</span>}
          {toolEntries.length > 0 && <span>🔧{toolEntries.length}</span>}
        </span>
        {durationMs != null && <span className="exec-agent-cost">{formatDuration(durationMs)}</span>}
        {runningEntryCount > 0 && <span className="exec-agent-live">● {runningEntryCount} 进行中</span>}
        <span className={`exec-agent-caret ${open ? 'open' : ''}`}>▸</span>
      </div>
      {open && group.entries.length > 0 && (
        <div className="exec-agent-body">
          {actionEntries.map((e, i) => <ActionRow key={`a-${i}`} entry={e} />)}
          {llmEntries.map((e, i) => <LlmRow key={`l-${i}`} entry={e} />)}
          {toolEntries.map((e, i) => <ToolRow key={`t-${i}`} entry={e} />)}
        </div>
      )}
    </div>
  );
}

function ActionRow({ entry }: { entry: ActionEntry }) {
  return (
    <div className={`exec-row action ${entry.status}`}>
      <div className="exec-row-head">
        <span className="exec-row-icon">🎯</span>
        <span className="exec-row-title">{entry.name}</span>
        {entry.durationMs != null && <span className="exec-row-cost">{formatDuration(entry.durationMs)}</span>}
        <span className={`exec-row-badge ${entry.status}`}>{statusText(entry.status)}</span>
      </div>
    </div>
  );
}

function LlmRow({ entry }: { entry: LlmEntry }) {
  const [open, setOpen] = useState(entry.status === 'running');
  return (
    <div className={`exec-row llm ${entry.status}`}>
      <div className="exec-row-head" onClick={() => setOpen(v => !v)}>
        <span className="exec-row-icon">🧠</span>
        <span className="exec-row-title">LLM · {entry.model}</span>
        {entry.durationMs != null && <span className="exec-row-cost">{formatDuration(entry.durationMs)}</span>}
        <span className={`exec-row-badge ${entry.status}`}>{statusText(entry.status)}</span>
        <span className={`exec-panel-caret ${open ? 'open' : ''}`}>▸</span>
      </div>
      {open && (
        <div className="exec-row-body">
          {entry.promptPreview && <details className="exec-block"><summary>Prompt</summary><pre>{entry.promptPreview}</pre></details>}
          {entry.responsePreview && <details className="exec-block" open><summary>Response</summary><pre>{entry.responsePreview}</pre></details>}
        </div>
      )}
    </div>
  );
}

function ToolRow({ entry }: { entry: ToolEntry }) {
  const [open, setOpen] = useState(false);
  return (
    <div className={`exec-row tool ${entry.status}`}>
      <div className="exec-row-head" onClick={() => setOpen(v => !v)}>
        <span className="exec-row-icon">🔧</span>
        <span className="exec-row-title">{entry.tool}</span>
        {entry.durationMs != null && <span className="exec-row-cost">{formatDuration(entry.durationMs)}</span>}
        <span className={`exec-row-badge ${entry.status}`}>{statusText(entry.status)}</span>
        <span className={`exec-panel-caret ${open ? 'open' : ''}`}>▸</span>
      </div>
      {open && (
        <div className="exec-row-body">
          {entry.input && <details className="exec-block"><summary>Input</summary><pre>{entry.input}</pre></details>}
          {entry.output && <details className="exec-block" open><summary>Output</summary><pre>{entry.output}</pre></details>}
        </div>
      )}
    </div>
  );
}

const UserMessage = memo(function UserMessage({ msg }: { msg: ChatMessage }) {
  return (
    <div className="chat-block user">
      <div className="chat-row user">
        <div className="chat-avatar">👤</div>
        <div className="chat-bubble-wrap">
          <div className="chat-bubble-header user">
            <span className="chat-bubble-name">你</span>
          </div>
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
  // 工具影响级别判断
  const getImpact = (toolName: string): { level: 'read' | 'write' | 'execute'; label: string; icon: string } => {
    const lower = toolName.toLowerCase();
    if (lower.includes('delete') || lower.includes('remove') || lower.includes('drop'))
      return { level: 'write', label: '高风险：删除操作', icon: '⚠️' };
    if (lower.includes('write') || lower.includes('create') || lower.includes('edit') || lower.includes('append') || lower.includes('move') || lower.includes('rename'))
      return { level: 'write', label: '写入操作', icon: '✏️' };
    if (lower.includes('exec') || lower.includes('run') || lower.includes('command') || lower.includes('shell') || lower.includes('bash'))
      return { level: 'execute', label: '执行操作', icon: '⚡' };
    return { level: 'read', label: '读取操作', icon: '📖' };
  };

  const hasWrite = pending.tools.some(t => getImpact(t.name).level !== 'read');

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>🔐 工具执行确认</h3>

        {/* 上下文说明 */}
        <div className="confirm-context">
          <span className="context-label">为什么需要确认</span>
          AI 正在处理你的请求，需要执行以下
          <strong>{hasWrite ? '写/执行' : '读取'}</strong>
          操作。这些操作
          <strong>{hasWrite ? '可能会修改文件或运行命令' : '不会对你的系统造成任何影响'}</strong>，
          请确认后放行。
        </div>

        {pending.tools.map((t, i) => {
          const impact = getImpact(t.name);
          return (
            <div key={i} className="tool-card">
              <div className="tool-name">{impact.icon} {t.name}</div>
              <div className={`tool-impact ${impact.level}`}>
                {impact.label}
              </div>
              <pre>{t.input}</pre>
            </div>
          );
        })}
        <div className="modal-actions">
          <button className="btn primary" onClick={() => onDecide('once')}>✅ 允许这一次</button>
          <button className="btn" onClick={() => onDecide('turn')}>🔄 本回合允许</button>
          <button className="btn" onClick={() => onDecide('always')}>♾️ 永久允许</button>
          <button className="btn danger" onClick={() => onDecide('deny')}>🚫 拒绝</button>
        </div>
        <div className="modal-hint">
          · 允许这一次：仅当前这次调用<br />
          · 本回合允许：本次对话中同类操作不再询问<br />
          · 永久允许：本工作区以后都不再询问（可在会话侧栏「🔐 授权」撤销）<br />
          · 拒绝：AI 会收到拒绝信息，可能会尝试其他方案或终止任务
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
  const [tab, setTab] = useState<'sessions' | 'files'>('sessions');
    const [authList, setAuthList] = useState<{ id: number; toolName: string; createdAt: string }[]>([]);
    const [authOptions, setAuthOptions] = useState<{ name: string; displayName: string }[]>([]);
  const [statusHint, setStatusHint] = useState('');
  
  
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [filePath, setFilePath] = useState('');
  interface OpenFile { entry: FileEntry; kind: 'text' | 'image'; content?: string; error?: string; loading: boolean; }
  const [openFiles, setOpenFiles] = useState<OpenFile[]>([]);
  const [activeFileTab, setActiveFileTab] = useState<string | null>(null);
  const [panelWidth, setPanelWidth] = useState(260);
  const [resizing, setResizing] = useState<'left' | 'right' | null>(null);
  const [skillName, setSkillName] = useState<string>('');
  const [availableSkills, setAvailableSkills] = useState<{ name: string; description: string; scope: string }[]>([]);
  const [traceWidth, setTraceWidth] = useState(380);
  const [traceCollapsed, setTraceCollapsed] = useState<boolean>(() => {
    try { return localStorage.getItem('chat:trace-collapsed') === '1'; } catch { return false; }
  });
  useEffect(() => {
    try { localStorage.setItem('chat:trace-collapsed', traceCollapsed ? '1' : '0'); } catch {}
  }, [traceCollapsed]);
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

  const [viewMode, setViewMode] = useState<'chat' | 'stream'>('chat');
  const [execEvents, setExecEvents] = useState<import('../components/ExecutionStream').StreamEvent[]>([]);
  const execEventsRef = useRef(execEvents);
  execEventsRef.current = execEvents;

  const handleWsEvent = useCallback((evt: StreamEvent) => {
    lastEventAtRef.current = Date.now();

    // 根据事件路由表判断去向
    const route = EVENT_ROUTE[evt.type] || 'exec';

    // 1. 收集到执行流瀑布流（exec + both）
    if (route === 'exec' || route === 'both') {
      const streamEvtData: any = {
        type: evt.type,
        timestamp: Date.now(),
        content: evt.content,
      };
      // 解析 content JSON 获取 agentName、processId、data 等
      try {
        if (evt.content) {
          const parsed = JSON.parse(evt.content);
          if (parsed && typeof parsed === 'object') {
            // 尝试从嵌套字段提取 data
            if (parsed.data && typeof parsed.data === 'object') {
              streamEvtData.data = parsed.data;
            } else {
              streamEvtData.data = parsed;
            }
            streamEvtData.agentName = parsed.agentName || parsed.agent;
            streamEvtData.processId = parsed.processId;
            streamEvtData.parentProcessId = parsed.parentProcessId;
          }
        }
      } catch {
        // content 不是 JSON，直接用 content 作为文本
      }
      setExecEvents((prev) => [...prev, streamEvtData].slice(-500));
    }

    // 2. 刷新/重连后 running=false，但后端 Agent 可能仍在运行 —— 收到数据事件即自动复位
    if (DATA_EVENT_TYPES.has(evt.type)) {
      const cur = getChatSession(key);
      if (!cur.running) {
        patch({ running: true, error: '' });
      }
    }

    // 3. 聊天视图事件（chat + both）走 reduceMessage
    if (route === 'chat' || route === 'both') {
      if (evt.type === 'tool') {
        // 工具开始执行：先入队 + 更新活跃集合，再 flush 保证一起渲染（避免闪烁）
        pendingEvtsRef.current.push(evt);
        updateChatSession(key, (c) => ({
          ...c,
          activeTools: c.activeTools.includes(evt.content) ? c.activeTools : [...c.activeTools, evt.content],
        }));
        flushNow();
      } else if (evt.type === 'subagent') {
        // 子 Agent 开始：先入队 + 更新活跃集合，再 flush 保证一起渲染
        pendingEvtsRef.current.push(evt);
        updateChatSession(key, (c) => ({
          ...c,
          activeSubagents: c.activeSubagents.includes(evt.content) ? c.activeSubagents : [...c.activeSubagents, evt.content],
        }));
        flushNow();
      } else if (evt.type === 'text' || evt.type === 'reasoning') {
        // text/reasoning 增量走统一批量渲染（32ms 节流）
        pendingEvtsRef.current.push(evt);
        scheduleFlush();
      } else if (['tool_args', 'tool_result', 'subagent_text'].includes(evt.type)) {
        // 其他 chat 视图事件也走批量渲染
        pendingEvtsRef.current.push(evt);
        scheduleFlush();
      }
    }

    // 4. 控制事件（独立处理，不受路由影响）
    if (evt.type === 'tool_end') {
      // 工具执行结束：从活跃集合移除
      updateChatSession(key, (c) => ({
        ...c,
        activeTools: c.activeTools.filter((t: string) => t !== evt.content),
      }));
    } else if (evt.type === 'subagent_end') {
      // 子 Agent 结束：从活跃集合移除
      updateChatSession(key, (c) => ({
        ...c,
        activeSubagents: c.activeSubagents.filter((s: string) => s !== evt.content),
      }));
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
      // Plan 校验消息：更新 plan 的 validation 字段，让 PlanProgressBar 显示警告
      if (evt.content.includes('Plan 校验')) {
        try {
          const jsonStr = evt.content.replace(/^⚠️ Plan 校验:\s*/, '');
          const validation = JSON.parse(jsonStr);
          updateChatSession(key, (c) => {
            const msgs = [...c.messages];
            // 搜索所有带 plan 的 AI 消息，从后往前找到最近的一个
            for (let i = msgs.length - 1; i >= 0; i--) {
              if (msgs[i].role === 'ai' && msgs[i].plan) {
                msgs[i] = { ...msgs[i], plan: { ...msgs[i].plan!, validation } };
                return { ...c, messages: msgs, error: '' };
              }
            }
            // 没找到 plan 消息（时序竞争：error 先于 plan 到达），存到 error 字段兜底
            return { ...c, error: evt.content };
          });
        } catch {
          patch({ error: evt.content });
        }
      } else {
        patch({ error: evt.content });
      }
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
    } else if (evt.type === 'agent_action' || evt.type === 'tool_call' || evt.type === 'llm_call' || evt.type === 'subagent_lifecycle') {
      try {
        const json = JSON.parse(evt.content);
        const now = Date.now();
        updateChatSession(key, (c) => {
          const msgs = [...c.messages];
          const lastIdx = msgs.length - 1;
          let msg = lastIdx >= 0 && msgs[lastIdx].role === 'ai' ? msgs[lastIdx] : null;
          if (!msg) {
            msg = { role: 'ai', segments: [] };
            msgs.push(msg);
          }
          const log: ExecutionLogEntry[] = msg.executionLog ? [...msg.executionLog] : [];
          const pid = json.processId;
          const parentPid = json.parentProcessId;
          const agentName = json.agentName;

          if (evt.type === 'agent_action') {
            const status = (json.status === 'running') ? 'running' : (json.status === 'failed' ? 'failed' : 'done');
            const idx = log.findIndex(e => e.kind === 'action' && e.processId === pid && e.name === json.name && e.status === 'running');
            const entry: ActionEntry = {
              kind: 'action', name: json.name || '', description: json.description,
              index: json.index, total: json.total, durationMs: json.durationMs,
              status, timestamp: now, processId: pid, parentProcessId: parentPid, agentName,
            };
            if (idx >= 0) { log[idx] = { ...log[idx], ...entry, timestamp: log[idx].timestamp || now }; }
            else { log.push(entry); }
          } else if (evt.type === 'subagent_lifecycle') {
            const subPid = pid;
            if (json.status === 'start') {
              log.push({ kind: 'subagent', name: json.name || '', lifecycle: 'start', status: 'running', timestamp: now, processId: subPid, parentProcessId: parentPid, agentName } as SubagentEntry);
            } else {
              const idx = log.findIndex((e): e is SubagentEntry => e.kind === 'subagent' && e.processId === subPid && (e as SubagentEntry).lifecycle === 'start');
              if (idx >= 0) {
                log[idx] = { ...(log[idx] as SubagentEntry), lifecycle: 'end', status: 'done', durationMs: json.durationMs };
              } else {
                log.push({ kind: 'subagent', name: json.name || '', lifecycle: 'end', status: 'done', durationMs: json.durationMs, timestamp: now, processId: subPid, parentProcessId: parentPid, agentName } as SubagentEntry);
              }
            }
          } else if (evt.type === 'tool_call') {
            const toolStatus = json.type === 'tool_call_start' ? 'running' : 'done';
            const entry: ToolEntry = {
              kind: 'tool', tool: json.tool || '', input: json.input || '',
              output: json.output, durationMs: json.durationMs,
              status: toolStatus, correlationId: json.correlationId, action: json.action,
              timestamp: now, processId: pid, parentProcessId: parentPid, agentName,
            };
            if (toolStatus === 'running') {
              log.push(entry);
            } else {
              const idx = log.findIndex((e): e is ToolEntry => e.kind === 'tool' && e.processId === pid && (e as ToolEntry).tool === json.tool && e.status === 'running');
              if (idx >= 0) { log[idx] = { ...(log[idx] as ToolEntry), output: json.output, durationMs: json.durationMs, status: 'done' }; }
              else { log.push(entry); }
            }
          } else if (evt.type === 'llm_call') {
            const llmStatus = json.type === 'llm_call_start' ? 'running' : 'done';
            const entry: LlmEntry = {
              kind: 'llm', model: json.model || '', action: json.action,
              promptPreview: json.promptPreview, messageCount: json.messageCount,
              responsePreview: json.responsePreview, responseLength: json.responseLength,
              durationMs: json.durationMs,
              status: llmStatus, timestamp: now, processId: pid, parentProcessId: parentPid, agentName,
            };
            if (llmStatus === 'running') {
              log.push(entry);
            } else {
              const idx = log.findIndex((e): e is LlmEntry => e.kind === 'llm' && e.processId === pid && (e as LlmEntry).model === json.model && e.status === 'running');
              if (idx >= 0) { log[idx] = { ...(log[idx] as LlmEntry), responsePreview: json.responsePreview, responseLength: json.responseLength, durationMs: json.durationMs, status: 'done' }; }
              else { log.push(entry); }
            }
          }

          msgs[lastIdx] = { ...msg, executionLog: log };
          return { ...c, messages: msgs };
        });
      } catch { /* ignore */ }
    } else if (evt.type === 'plan' || evt.type === 'step') {
      try {
        const json = JSON.parse(evt.content);
        updateChatSession(key, (c) => {
          const msgs = [...c.messages];
          const lastIdx = msgs.length - 1;
          let msg = lastIdx >= 0 && msgs[lastIdx].role === 'ai' ? msgs[lastIdx] : null;
          if (!msg) {
            msg = { role: 'ai', segments: [] };
            msgs.push(msg);
          }
          let plan = msg.plan;
          if (evt.type === 'plan') {
            const steps = (json.steps || []).map((s: any, i: number) => ({
              name: s.name || '', description: s.description || '',
              status: 'pending' as const, index: i,
              preconditions: s.preconditions || {},
              effects: s.effects || {},
            }));
            plan = {
              goal: json.goal || '', goalDescription: json.goalDescription || '',
              goalPreconditions: json.goalPreconditions || {},
              goalKnownConditions: json.goalKnownConditions || [],
              totalSteps: json.totalSteps || steps.length, steps,
              agent: json.agent || undefined,
            };
          } else if (evt.type === 'step' && plan) {
            const stepStatus = (json.status || 'pending') as PlanStep['status'];
            if (typeof json.index === 'number' && json.index >= 0 && json.index < plan.steps.length) {
              plan = { ...plan, steps: plan.steps.map((s, i) =>
                i === json.index ? { ...s, status: stepStatus, ...(json.name ? { name: json.name } : {}) } : s
              ) };
            } else if (json.name) {
              const idx = plan.steps.findIndex(s => s.name === json.name);
              if (idx >= 0) {
                plan = { ...plan, steps: plan.steps.map((s, i) =>
                  i === idx ? { ...s, status: stepStatus } : s
                ) };
              }
            }
          }
          msgs[lastIdx] = { ...msg, plan };
          return { ...c, messages: msgs };
        });
      } catch { /* ignore */ }
    } else if (evt.type === 'end') {
      flushNow();
      patch({ running: false, activeTools: [], activeSubagents: [] });
      // executionLog 保留到下一轮开始时再清，让用户能回看；在 text 事件第一次出现时清空
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
      let pendingPlan: PlanState | null = null;
      let pendingValidation: PlanValidation | null = null;
      const pendingSteps: PlanStep[] = [];
      for (const b of box) {
        if (b.type === 'USER') {
          cur = null;
          msgs.push({ id: newMessageId(), role: 'user', segments: [{ type: 'text', content: b.content }], attachments: (b.images || []).map((src) => ({ name: 'image', mimeType: 'image/png' })) });
          pendingPlan = null;
          pendingSteps.length = 0;
        } else if (b.type === 'PLAN') {
          try {
            const pj = JSON.parse(b.content);
            const steps: PlanStep[] = (pj.steps || []).map((s: any, i: number) => ({
              name: s.name || '', description: s.description || '',
              status: 'pending', index: i,
              preconditions: s.preconditions || {},
              effects: s.effects || {},
            }));
            pendingPlan = {
              goal: pj.goal || '', goalDescription: pj.goalDescription || '',
              goalPreconditions: pj.goalPreconditions || {},
              goalKnownConditions: pj.goalKnownConditions || [],
              totalSteps: pj.totalSteps || steps.length, steps,
              agent: pj.agent || undefined,
              validation: pendingValidation || undefined,
            };
            // 应用后清除暂存
            pendingValidation = null;
            pendingSteps.length = 0;
            pendingSteps.push(...steps);
          } catch { /* 忽略格式错误 */ }
        } else if (b.type === 'STEP') {
          try {
            const sj = JSON.parse(b.content);
            if (pendingPlan && typeof sj.index === 'number' && sj.index >= 0 && sj.index < pendingPlan.steps.length) {
              pendingPlan.steps[sj.index].status = (sj.status || 'pending') as PlanStep['status'];
              if (sj.name) pendingPlan.steps[sj.index].name = sj.name;
            } else if (pendingPlan && sj.name) {
              const idx = pendingPlan.steps.findIndex(s => s.name === sj.name);
              if (idx >= 0) {
                pendingPlan.steps[idx].status = (sj.status || 'pending') as PlanStep['status'];
              }
            }
              if (!cur) { cur = { role: 'ai', segments: [] }; msgs.push(cur); }
              if (!cur.executionLog) cur.executionLog = [];
              cur.executionLog.push({
                kind: 'action',
                name: sj.name || '',
                description: sj.description || '',
                index: sj.index,
                total: sj.total,
                durationMs: sj.durationMs,
                status: (sj.status === 'failed' ? 'failed' : 'done') as ExecStatus,
                processId: sj.processId,
                parentProcessId: sj.parentProcessId,
                agentName: sj.agentName,
                timestamp: b.seq,
              });
          } catch { /* 忽略 */ }
        } else if (b.type === 'TOOL_CALL') {
          if (!cur) { cur = { role: 'ai', segments: [] }; msgs.push(cur); }
          if (!cur.executionLog) cur.executionLog = [];
          try {
            const tj = JSON.parse(b.content);
            cur.executionLog.push({
              kind: 'tool',
              tool: tj.tool || b.toolName || '',
              input: tj.input || b.toolArgs || '',
              output: tj.output || b.toolResult,
              durationMs: tj.durationMs,
              status: 'done' as const,
              correlationId: tj.correlationId,
              action: tj.action,
              processId: tj.processId,
              parentProcessId: tj.parentProcessId,
              agentName: tj.agentName,
              timestamp: b.seq,
            });
          } catch {
            cur.executionLog.push({
              kind: 'tool',
              tool: b.toolName || '',
              input: b.toolArgs || '',
              output: b.toolResult,
              status: 'done' as const,
              timestamp: b.seq,
            });
          }
        } else if (b.type === 'LLM_CALL') {
          if (!cur) { cur = { role: 'ai', segments: [] }; msgs.push(cur); }
          if (!cur.executionLog) cur.executionLog = [];
          try {
            const lj = JSON.parse(b.content);
            cur.executionLog.push({
              kind: 'llm',
              model: lj.model || '',
              promptPreview: lj.promptPreview,
              responsePreview: lj.responsePreview,
              responseLength: lj.responseLength,
              durationMs: lj.durationMs,
              status: 'done' as const,
              action: lj.action,
              processId: lj.processId,
              parentProcessId: lj.parentProcessId,
              agentName: lj.agentName,
              timestamp: b.seq,
            });
          } catch { /* 忽略 */ }
        } else if (b.type === 'AGENT_ACTION') {
          if (!cur) { cur = { role: 'ai', segments: [] }; msgs.push(cur); }
          if (!cur.executionLog) cur.executionLog = [];
          try {
            const aj = JSON.parse(b.content);
            const rawStatus = (aj.status === 'running') ? 'running' : (aj.status === 'failed' ? 'failed' : 'done');
            cur.executionLog.push({
              kind: 'action',
              name: aj.name || '',
              description: aj.description,
              index: aj.index,
              total: aj.total,
              durationMs: aj.durationMs,
              status: rawStatus as ExecStatus,
              processId: aj.processId,
              parentProcessId: aj.parentProcessId,
              agentName: aj.agentName,
              timestamp: b.seq,
            });
          } catch { /* 忽略 */ }
          } else if (b.type === 'SUBAGENT_LIFECYCLE') {
            if (!cur) { cur = { role: 'ai', segments: [] }; msgs.push(cur); }
            if (!cur.executionLog) cur.executionLog = [];
            try {
              const sj = JSON.parse(b.content);
              cur.executionLog.push({
                kind: 'subagent',
                name: sj.name || '',
                lifecycle: sj.status === 'start' ? 'start' : 'end',
                status: sj.status === 'failed' ? 'failed' : (sj.status === 'start' ? 'running' : 'done'),
                processId: sj.processId,
                parentProcessId: sj.parentProcessId,
                agentName: sj.name || '',
                durationMs: sj.durationMs,
                timestamp: b.seq,
              } as SubagentEntry);
            } catch { /* 忽略 */ }
        } else if (b.type === 'AI_TEXT' || b.type === 'THINKING' || b.type === 'SUBAGENT' || b.type === 'TOOL_RESULT') {
          if (!cur) {
            if (pendingPlan) {
              cur = { role: 'ai', segments: [], plan: pendingPlan };
              pendingPlan = null;
              msgs.push(cur);
            } else {
              cur = { role: 'ai', segments: [] };
              msgs.push(cur);
            }
          } else if (pendingPlan && !cur.plan) {
            cur.plan = pendingPlan;
            pendingPlan = null;
          }
          if (b.type === 'AI_TEXT') cur.segments.push({ type: 'text', content: b.content });
          else if (b.type === 'THINKING') cur.segments.push({ type: 'reasoning', content: b.content });
          else if (b.type === 'SUBAGENT') cur.segments.push({ type: 'subagent', name: b.subagentName || '', content: b.content });
        } else if (b.type === 'PLAN_VALIDATION') {
          try {
            const vj = JSON.parse(b.content);
            pendingValidation = {
              valid: vj.valid ?? false,
              invalidSteps: vj.invalidSteps || [],
              validSteps: vj.validSteps || [],
              availableActions: vj.availableActions || [],
              message: vj.message || '',
            };
          } catch { /* 忽略 */ }
        } else if (b.type === 'SYSTEM' || b.type === 'CONFIRM') {
          // SYSTEM 不进入消息流；CONFIRM 也不
        }
      }
      // 最后一条 AI 消息如果只有 plan/executionLog 没有 segments，也保留
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
      const [all, roleRes] = await Promise.all([
          getJson<{ scope: string; name: string; description: string }[]>(
        `/api/skills?workspaceId=${encodeURIComponent(wid)}`),
          getJson<{ name: string; displayName: string; role: string; active: boolean }[]>('/api/roles'),
        ]);
      // 包含 system/global/workspace 三级
      const skills = all.filter(s => s.scope === 'system' || s.scope === 'global' || s.scope === 'workspace');
        const roles = roleRes.filter(r => r.active).map(r => ({ scope: 'role', name: r.name, description: r.displayName || r.role || '角色' }));
        setAvailableSkills([...roles, ...skills]);
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
      requestAnimationFrame(() => {
        scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
      });
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
      { id: newMessageId(), role: 'user', segments: [{ type: 'text', content: text }], attachments: myAtts.map((a) => ({ name: a.name, mimeType: a.mimeType })) },
    ]);
    patch({ running: true, error: '', pending: null }); // 新对话开始：关闭可能残留的确认弹窗
    setExecEvents([]); // 新对话清空上一轮执行流
      requestAnimationFrame(() => {
        scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
      });
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
        <div className="topbar-left">
          <span className="ws-name">💬 {workspace?.name}</span>
          {workspace?.agentName && <span className="agent-badge">🤖 {workspace.agentName}</span>}
          <span className="ws-path">{workspace?.path}</span>
        </div>

        {/* 视图切换：从消息流里移到顶栏，避免挤压聊天内容 */}
        <div className="view-mode-tabs topbar-tabs">
          <button
            className={`view-tab ${viewMode === 'chat' ? 'active' : ''}`}
            onClick={() => setViewMode('chat')}
            title="聊天视图"
          >💬 聊天</button>
          <button
            className={`view-tab ${viewMode === 'stream' ? 'active' : ''}`}
            onClick={() => { setViewMode('stream'); setTraceCollapsed(false); }}
            title="执行流视图（也可展开右侧面板）"
          >⚡ 执行流 <span className="tab-count">{execEvents.length}</span></button>
        </div>

        {/* 运行状态胶囊：Agent / 子 Agent / Tool 合并为紧凑一行 */}
        <div className="topbar-right">
          {running && (
            <span className="status-badge running">
              <span className="spinner-tiny" /> 主 Agent 运行中
            </span>
          )}
          {activeSubagents.length > 0 && (
            <span className="status-badge subagent cluster" title={'子 Agent：' + activeSubagents.join('、')}>
              🤖 ×{activeSubagents.length}
            </span>
          )}
          {activeTools.length > 0 && (
            <span className="status-badge tool cluster" title={'运行中工具：' + activeTools.join('、')}>
              🔧 ×{activeTools.length}
            </span>
          )}
        </div>
      </div>

      <div className="chat-scroll" ref={scrollRef} onScroll={(e) => {
        const el = e.currentTarget;
        stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 150;
      }}>
        <div className="chat-messages">
          {messages.length === 0 && !running && viewMode === 'chat' && (
            <div className="empty">
              <h3>开始对话</h3>
              <p className="hint">AI 只能在你指定的工作区目录内工作</p>
              <p className="hint">右侧是 ⚡ 执行流面板：实时显示 LLM 调用、工具调用、子 Agent 运行轨迹</p>
            </div>
          )}

          {viewMode === 'stream' ? (
            <ExecutionStream
              events={execEvents}
              workspaceId={workspaceId}
              sessionId={sessionId}
              completed={!running}
            />
          ) : (
            <>
              {messages.map((m, i) => {
                if (m.role === 'user') return <UserMessage key={m.id || i} msg={m} />;
                if (m.role === 'ai' && m.segments.length === 0 && !m.plan && (!m.executionLog || m.executionLog.length === 0)) {
                  return null;
                }
                const prev = i > 0 && messages[i - 1].role === 'user' ? messages[i - 1] : null;
                void prev;
                return <AiMessage key={m.id || i} msg={m} isStreaming={running && i === messages.length - 1} agentLabel={workspace?.agentName || workspace?.name} running={running && i === messages.length - 1} />;
              })}
            </>
          )}
        </div>
      </div>

      {/* 输入 dock：压缩为 2 行。第一行：状态 + 待发队列胶囊；第二行：附件条/错误/skill/textarea/按钮 */}
      <div className="chat-input-dock">
        <div className="dock-row-1">
          <div className="chat-status">
            {running
              ? (statusHint || '🤖 AI 正在输出...')
              : (pending ? '🔐 AI 正在等待你的确认...' : '💬 输入消息开始对话')}
          </div>
          {chat.messageQueue.length > 0 && (
            <span className="queue-pill" title={chat.messageQueue.map(q => q.text).join('\n')}>
              📥 {chat.messageQueue.length} 条待发
            </span>
          )}
        </div>

        {chat.messageQueue.length > 0 && (
          <div className="queue-bar compact">
            {chat.messageQueue.map((item, i) => (
              <div key={item.id} className={`queue-item ${i === 0 ? 'next' : ''}`}>
                <span className="queue-num">{i === 0 ? '▶' : i + 1}</span>
                <span className="queue-text" title={item.text}>{item.text}</span>
                {item.guides.length > 0 && <span className="queue-guides" title={item.guides.join(', ')}>🧭 {item.guides.length}</span>}
                {(running || pending) && (
                  <button className="queue-intervene" title="插队" onClick={() => interveneQueueItem(i)}>⚡</button>
                )}
                <button className="queue-del" onClick={() => {
                  if (i === 0) updateChatSession(key, (c) => ({ ...c, messageQueue: c.messageQueue.slice(1) }));
                  else updateChatSession(key, (c) => ({ ...c, messageQueue: c.messageQueue.filter((_, j) => j !== i) }));
                }}>✕</button>
              </div>
            ))}
          </div>
        )}

        {error && <div className="error-box compact">
          {friendlyError(error)}
          {error !== friendlyError(error) && (
            <details style={{ marginTop: 4, fontSize: 11 }}>
              <summary style={{ cursor: 'pointer', color: 'var(--text-tertiary)' }}>技术详情</summary>
              <pre style={{ margin: '4px 0 0', padding: 8, background: 'rgba(0,0,0,0.05)', borderRadius: 4, overflow: 'auto', maxHeight: 120 }}>{error}</pre>
            </details>
          )}
        </div>}

        {attachments.length > 0 && (
          <div className="attachment-bar compact">
            {attachments.map((a, i) => (
              <span key={i} className="attach-chip">
                📎 {a.name}
                <button onClick={() => setAttachments((prev) => prev.filter((_, j) => j !== i))}>✕</button>
              </span>
            ))}
          </div>
        )}

        <div className="dock-row-2">
          <label className="btn small dock-btn" title="上传附件">
            📎
            <input type="file" multiple style={{ display: 'none' }} onChange={pickFiles} />
          </label>

          {availableSkills.length > 0 && (
            <select
              className="skill-select dock-btn"
              value={skillName}
              onChange={(e) => setSkillName(e.target.value)}
              title="选择角色或 Skill"
            >
              <option value="">🧭 默认</option>
              {availableSkills.map((s) => (
                <option key={s.scope + ':' + s.name} value={s.name}>
                  🧭 {s.name}{s.description ? ` — ${s.description}` : ''}
                </option>
              ))}
            </select>
          )}

          <textarea
            ref={textareaRef}
            className="dock-textarea"
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
              ? `运行中 — Enter 入队 · Ctrl+Enter 插队 · Shift+Enter 换行`
              : '输入消息，Enter 发送，Shift+Enter 换行，可粘贴截图'}
            rows={1}
          />

          {running && (
            <button
              className="btn danger dock-btn"
              onClick={() => {
                getChatSocket().send({ type: 'stop', workspaceId, sessionId });
                console.log('[ws-send] stop');
              }}
              title="停止当前回复"
            >⏹</button>
          )}
          {running && (input.trim() || attachments.length > 0) && (
            <button className="btn intervene-btn dock-btn" onClick={interveneNow} title="插队：Ctrl+Enter">⚡</button>
          )}
          <button className="btn primary dock-btn send-btn" onClick={submit} title={running ? '加入队列' : '发送消息'}>
            {running ? '📥' : '➤'}
          </button>
        </div>
      </div>
    </div>
  );

  // resizer 拖拽：左侧栏（'left'）和右侧 Trace 栏（'right'）
  const onResizerDown = (side: 'left' | 'right') => (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setResizing(side);
    const startX = e.clientX;
    const startWLeft = panelWidth;
    const startWRight = traceWidth;
    const onMove = (ev: MouseEvent) => {
      if (side === 'left') {
        const delta = startX - ev.clientX;
        setPanelWidth(Math.max(220, Math.min(420, startWLeft + delta)));
      } else {
        const delta = ev.clientX - startX;
        setTraceWidth(Math.max(320, Math.min(640, startWRight + delta)));
      }
    };
    const onUp = () => {
      setResizing(null);
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
      case 'files':
        return filePanel;
    }
  };

  return (
    <div className={`chat-page ${resizing ? `resizing-${resizing}` : ''}`}>
      {/* 左栏：会话 / 文件 / 工具白名单 */}
      <div className="chat-side left-side" style={{ width: panelWidth }}>
        <div className="side-tabs">
          <button className={`btn small ${tab === 'sessions' ? 'primary' : ''}`} onClick={() => setTab('sessions')}>💬 会话</button>
          <button className={`btn small ${tab === 'files' ? 'primary' : ''}`} onClick={() => setTab('files')}>📁 文件</button>
          {openFiles.length > 0 && (
            <button className="btn small" onClick={() => { setOpenFiles([]); setActiveFileTab(null); }} title="关闭所有文件">📑 ×</button>
          )}
        </div>
        <div className="side-body">
          {renderToolPanel()}
        </div>
      </div>

      <div className="chat-resizer left-resizer" onMouseDown={onResizerDown('left')} title="拖拽调整左栏宽度" />

      {/* 中栏：聊天主区 */}
      {chatArea}

      {/* 右栏：⚡ 执行流 Trace（Cursor 风格） */}
      {!traceCollapsed && (
        <>
          <div className="chat-resizer right-resizer" onMouseDown={onResizerDown('right')} title="拖拽调整 Trace 宽度" />
          <aside className="trace-panel" style={{ width: traceWidth }}>
            <div className="trace-header">
              <div className="trace-title">
                <span className="trace-icon">⚡</span>
                <span>执行流</span>
                <span className="trace-count">{execEvents.length}</span>
                {running && <span className="trace-running">
                  <span className="spinner-tiny" /> 运行中
                </span>}
              </div>
              <div className="trace-actions">
                <button
                  className="btn small"
                  onClick={() => setViewMode(viewMode === 'stream' ? 'chat' : 'stream')}
                  title={viewMode === 'stream' ? '切换中栏到聊天' : '切换中栏到执行流'}
                >
                  {viewMode === 'stream' ? '💬' : '⚡'}
                </button>
                <button
                  className="btn small trace-close"
                  onClick={() => setTraceCollapsed(true)}
                  title="折叠执行流面板（Alt+X）"
                >×</button>
              </div>
            </div>
            <div className="trace-body">
              <ExecutionStream
                events={execEvents}
                workspaceId={workspaceId}
                sessionId={sessionId}
                completed={!running}
              />
            </div>
          </aside>
        </>
      )}
      {traceCollapsed && (
        <button
          className="trace-fab"
          onClick={() => setTraceCollapsed(false)}
          title="展开执行流面板（Alt+X）"
        >
          ⚡ <span className="trace-fab-count">{execEvents.length}</span>
          {running && <span className="spinner-tiny" />}
        </button>
      )}

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
