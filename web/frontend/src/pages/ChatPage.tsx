import {memo, useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {del, getJson, postJson, putJson, type StreamEvent} from '../api';
import {marked} from 'marked';
import DOMPurify from 'dompurify';
import type {AttachmentPayload, ChatMessage, PendingConfirm, QueueItem, Segment, SubStep} from '../chatStore';
import {
    chatKey,
    getActiveSession,
    getChatSession,
    setActiveSession,
    updateChatSession,
    useChatSession
} from '../chatStore';
import {getChatSocket, subscribeChatSocket} from '../chatSocket';
import {parseNames} from '../scenarioBinding';

// ============ 类型 ============
interface Workspace { workspaceId: string; name: string; agentName?: string; path: string; description: string; }
interface SessionItem { id: string; workspaceId: string; title: string; createdAt: string; }
interface BoxMessage {
  id?: string; type: string; content: string; toolName?: string;
  toolArgs?: string; toolResult?: string; subagentName?: string; images?: string[]; seq: number;
}
interface Attachment { name: string; mimeType: string; base64Data: string; }

/** 扩展名 → MIME，兜底 File.type 为空的情况（Windows 缺注册表项时 type 常为 ''） */
const EXT_MIME: Record<string, string> = {
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif',
  webp: 'image/webp', bmp: 'image/bmp', svg: 'image/svg+xml', avif: 'image/avif',
};

/**
 * 解析附件 MIME：优先 File.type，其次 FileReader 生成的 data URL 前缀，最后按扩展名兜底。
 *
 * 必须有兜底：mimeType 为空会让后端 buildUserMessage 走文本分支，
 * 把图片二进制按 UTF-8 强解成乱码，表现为「图片传输损坏」。
 */
function resolveMimeType(f: File, dataUrl: string): string {
  if (f.type && f.type !== 'application/octet-stream') return f.type;
  const m = /^data:([^;,]+)[;,]/.exec(dataUrl || '');
  if (m && m[1] && m[1] !== 'application/octet-stream') return m[1];
  const ext = (f.name.split('.').pop() || '').toLowerCase();
  return EXT_MIME[ext] || 'application/octet-stream';
}

// 工具目录（/api/tools/builtin 返回的结构）
interface ToolParamDef { name: string; required: boolean; description: string; }
interface ToolDef { name: string; displayName: string; description: string; group: string; requiresConfirm: boolean; params: ToolParamDef[]; }

/** 工具名称 → 可读描述（loadTools 时填充，ToolCallCard 自解释用） */
const toolDescCache = new Map<string, string>();

/** 截取工具描述的第一句作为卡片副标题 */
function shortDesc(d: string): string {
  if (!d) return '';
  const m = d.match(/^[^。\n]*[。]?/);
  return m && m[0] ? m[0] : d;
}

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
const DATA_EVENT_TYPES = new Set(['text', 'reasoning', 'tool', 'tool_args', 'tool_result', 'subagent', 'subagent_text',
  'subagent_reasoning', 'subagent_tool', 'subagent_tool_args', 'subagent_tool_result']);

type SubStepEvent = 'text' | 'reasoning' | 'tool' | 'toolArgs' | 'toolResult';

/**
 * 把子 Agent 的一条增量并入其 subagent 段的 steps 序列。
 *
 * 线性关键：连续同类的 text/reasoning 合并进最后一步，遇到不同类型就开新步，
 * 从而保留「思考 → 调工具 → 继续说」的真实时序，而不是把三者拍平成一坨。
 *
 * 定卡关键：有 subId 时按实例精确定位，只在没有 subId（历史转录）时才退回按 name 找。
 * 并行派发的两个同角色子 Agent（如两个 code-expert）name 完全相同，仅按 name 归并会让
 * 两路输出交错进同一张卡片。
 */
function appendSubStep(
  segments: Segment[], name: string, kind: SubStepEvent, delta: string, state?: string,
  subId?: string,
): Segment[] {
  const segs = [...segments];
  // 找该子 Agent 最后一个段：优先按实例 id 精确匹配，无 id 时退回按名字（只并入最近一次调度）
  let idx = -1;
  for (let i = segs.length - 1; i >= 0; i--) {
    const s = segs[i];
    if (s.type !== 'subagent') continue;
    if (subId) {
      if (s.subId === subId) { idx = i; break; }
    } else if (s.name === name && !s.subId) {
      idx = i; break;
    }
  }
  if (idx < 0) {
    segs.push({
      type: 'subagent', name, content: '', running: true, startedAt: Date.now(), steps: [], subId,
    });
    idx = segs.length - 1;
  }
  const seg = segs[idx];
  const steps: SubStep[] = [...(seg.steps || [])];
  const lastStep = steps[steps.length - 1];

  switch (kind) {
    case 'text':
    case 'reasoning':
      if (lastStep && lastStep.kind === kind) {
        steps[steps.length - 1] = { ...lastStep, content: (lastStep.content || '') + delta };
      } else {
        steps.push({ kind, content: delta });
      }
      break;
    case 'tool':
      steps.push({ kind: 'tool', name: delta, args: '', running: true });
      break;
    case 'toolArgs':
      // 入参增量归属最近一个仍在执行的工具步
      for (let i = steps.length - 1; i >= 0; i--) {
        if (steps[i].kind === 'tool' && steps[i].running) {
          steps[i] = { ...steps[i], args: (steps[i].args || '') + delta };
          break;
        }
      }
      break;
    case 'toolResult':
      for (let i = steps.length - 1; i >= 0; i--) {
        if (steps[i].kind === 'tool' && steps[i].running) {
          steps[i] = { ...steps[i], result: delta, state, running: false };
          break;
        }
      }
      break;
  }
  // content 同步累积正文，供折叠预览与「是否有输出」判断复用
  const content = kind === 'text' ? (seg.content || '') + delta : seg.content;
  segs[idx] = { ...seg, steps, content };
  return segs;
}
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

// 工具结果状态推断：从 "(SUCCESS) ..." / "(ERROR) ..." 前缀提取
function toolStateOf(result?: string): 'success' | 'error' | 'empty' | '' {
  if (!result) return '';
  const m = result.match(/^\(([A-Z_]+)\)/);
  if (!m) return '';
  const s = m[1];
  if (s === 'SUCCESS') return 'success';
  if (s === 'ERROR' || s === 'FAIL' || s === 'FAILED') return 'error';
  if (s === 'EMPTY' || s === 'EMPTY_RESULT') return 'empty';
  return '';
}

// 工具参数格式化：能解析成 JSON 就美化，否则原样
function prettyJson(s: string): string {
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}

// 流式事件 → 消息列表（纯 reducer，可批量应用）
// 注意：必须不可变更新（每次创建新消息对象），否则 React.memo 按引用比较会跳过重渲染
function reduceMessage(prev: ChatMessage[], evt: StreamEvent): ChatMessage[] {
  const next = [...prev];
  const lastIdx = next.length - 1;
  const last = next[lastIdx];
  // 定位工具段并原地更新。
  // 优先按 toolCallId 精确匹配发起该次调用的卡片；并发工具调用下「最后一个 tool 段」
  // 会被后发起的调用抢占，导致先发起的卡片永远收不到自己的 result/tool_end。
  // 无 id（历史转录回放 / 旧后端）时退化为原有的就近匹配。
  const patchTool = (
    fn: (s: Segment) => Segment,
    opts?: { callId?: string; preferRunning?: boolean },
  ) => {
    if (!last || last.role !== 'ai') return;
    const segs = [...last.segments];
    const callId = opts?.callId;
    let idx = -1;
    if (callId) {
      for (let j = segs.length - 1; j >= 0; j--) {
        if (segs[j].type === 'tool' && segs[j].toolCallId === callId) { idx = j; break; }
      }
    }
    if (idx < 0) {
      // 兜底：优先命中仍在执行的工具段，再退到最后一个 tool 段
      if (opts?.preferRunning) {
        for (let j = segs.length - 1; j >= 0; j--) {
          if (segs[j].type === 'tool' && segs[j].running) { idx = j; break; }
        }
      }
      if (idx < 0) {
        for (let j = segs.length - 1; j >= 0; j--) {
          if (segs[j].type === 'tool') { idx = j; break; }
        }
      }
    }
    if (idx < 0) return;
    segs[idx] = fn(segs[idx]);
    next[lastIdx] = { ...last, segments: segs };
  };
  switch (evt.type) {
    case 'text': {
      if (last && last.role === 'ai') {
        const segs = [...last.segments];
        const li = segs.length - 1;
        if (li >= 0 && segs[li].type === 'text') {
          segs[li] = { ...segs[li], content: (segs[li].content || '') + evt.content };
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
          segs[li] = { ...segs[li], content: (segs[li].content || '') + evt.content };
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
      // 工具调用开始：新建一个结构化工具段（记录调用 id 以便后续事件精确配对）
      const seg: Segment = {
        type: 'tool',
        name: evt.content,
        toolCallId: evt.toolCallId,
        args: '',
        result: '',
        running: true,
      };
      if (last && last.role === 'ai') {
        next[lastIdx] = { ...last, segments: [...last.segments, seg] };
      } else {
        next.push({ role: 'ai', segments: [seg] });
      }
      break;
    }
    case 'tool_args': {
      patchTool((s) => ({ ...s, args: (s.args || '') + evt.content }), {
        callId: evt.toolCallId,
        preferRunning: true,
      });
      break;
    }
    case 'tool_result': {
      patchTool((s) => ({ ...s, result: (s.result || '') + evt.content }), {
        callId: evt.toolCallId,
        preferRunning: true,
      });
      break;
    }
    case 'tool_end': {
      // 优先按 toolCallId 精确关闭对应卡片；无 id 时按工具名匹配仍在执行的那次调用，
      // 再兜底关最后一个仍在执行的 tool 段
      if (last && last.role === 'ai') {
        const segs = [...last.segments];
        const name = evt.content;
        const callId = evt.toolCallId;
        let idx = -1;
        if (callId) {
          for (let j = segs.length - 1; j >= 0; j--) {
            if (segs[j].type === 'tool' && segs[j].toolCallId === callId) { idx = j; break; }
          }
        }
        if (idx < 0) {
          for (let j = segs.length - 1; j >= 0; j--) {
            const s = segs[j];
            if (s.type === 'tool' && s.running && (!name || s.name === name)) { idx = j; break; }
          }
        }
        if (idx < 0) {
          for (let j = segs.length - 1; j >= 0; j--) {
            if (segs[j].type === 'tool' && segs[j].running) { idx = j; break; }
          }
        }
        if (idx >= 0) {
          segs[idx] = { ...segs[idx], running: false };
          next[lastIdx] = { ...last, segments: segs };
        }
      }
      break;
    }
    case 'subagent': {
      const seg: Segment = {
        type: 'subagent', name: evt.content, content: '', running: true, startedAt: Date.now(),
        subId: evt.subId,
      };
      if (last && last.role === 'ai') {
        next[lastIdx] = { ...last, segments: [...last.segments, seg] };
      } else {
        next.push({ role: 'ai', segments: [seg] });
      }
      break;
    }
    case 'subagent_end': {
      if (last && last.role === 'ai') {
        const segs = [...last.segments];
        const name = evt.content;
        // 有 subId 时按实例精确关卡：并行同角色实例下，只按 name 找会关掉先起的那张，
        // 让仍在运行的另一张永久停在「执行中」。无 subId（历史转录）时保持原就近匹配。
        for (let j = segs.length - 1; j >= 0; j--) {
          const s = segs[j];
          if (s.type !== 'subagent' || !s.running) continue;
          if (evt.subId ? s.subId === evt.subId : s.name === name) {
            segs[j] = { ...s, running: false, endedAt: Date.now() };
            break;
          }
        }
        next[lastIdx] = { ...last, segments: segs };
      }
      break;
    }    case 'blackboard': {
      // 每条登记都是独立的不可变记录 → 追加新 segment，不合并进已有的
      let payload: { seq?: number; type?: string; author?: string; content?: string } = {};
      try {
        payload = JSON.parse(evt.content);
      } catch {
        // 后端保证是 JSON；解析失败时降级为纯文本展示，不能让整条消息渲染中断
        payload = { content: evt.content };
      }
      const seg: Segment = {
        type: 'blackboard',
        content: payload.content || '',
        bbSeq: payload.seq,
        bbType: payload.type || 'note',
        bbAuthor: payload.author || 'unknown',
      };
      if (last && last.role === 'ai') {
        next[lastIdx] = { ...last, segments: [...last.segments, seg] };
      } else {
        next.push({ role: 'ai', segments: [seg] });
      }
      break;
    }
    case 'subagent_text': {
      const sep = evt.content.indexOf('\u0001');
      const name = sep >= 0 ? evt.content.slice(0, sep) : '';
      const delta = sep >= 0 ? evt.content.slice(sep + 1) : evt.content;
      if (last && last.role === 'ai') {
        next[lastIdx] = { ...last, segments: appendSubStep(last.segments, name, 'text', delta, undefined, evt.subId) };
      }
      break;
    }
    case 'subagent_reasoning': {
      const sep = evt.content.indexOf('\u0001');
      const name = sep >= 0 ? evt.content.slice(0, sep) : '';
      const delta = sep >= 0 ? evt.content.slice(sep + 1) : evt.content;
      if (last && last.role === 'ai') {
        next[lastIdx] = { ...last, segments: appendSubStep(last.segments, name, 'reasoning', delta, undefined, evt.subId) };
      }
      break;
    }
    case 'subagent_tool': {
      const sep = evt.content.indexOf('\u0001');
      const name = sep >= 0 ? evt.content.slice(0, sep) : '';
      const toolName = sep >= 0 ? evt.content.slice(sep + 1) : evt.content;
      if (last && last.role === 'ai') {
        next[lastIdx] = { ...last, segments: appendSubStep(last.segments, name, 'tool', toolName, undefined, evt.subId) };
      }
      break;
    }
    case 'subagent_tool_args': {
      const sep = evt.content.indexOf('\u0001');
      const name = sep >= 0 ? evt.content.slice(0, sep) : '';
      const delta = sep >= 0 ? evt.content.slice(sep + 1) : evt.content;
      if (last && last.role === 'ai') {
        next[lastIdx] = { ...last, segments: appendSubStep(last.segments, name, 'toolArgs', delta, undefined, evt.subId) };
      }
      break;
    }
    case 'subagent_tool_result': {
      // 编码：name \u0001 state \u0001 result
      const p1 = evt.content.indexOf('\u0001');
      const rest = p1 >= 0 ? evt.content.slice(p1 + 1) : '';
      const p2 = rest.indexOf('\u0001');
      const name = p1 >= 0 ? evt.content.slice(0, p1) : '';
      const state = p2 >= 0 ? rest.slice(0, p2) : rest;
      const result = p2 >= 0 ? rest.slice(p2 + 1) : '';
      if (last && last.role === 'ai') {
        next[lastIdx] = {
          ...last,
          segments: appendSubStep(last.segments, name, 'toolResult', result, state, evt.subId),
        };
      }
      break;
    }
    case 'context': {
      // 系统提示类事件（工具失败护栏/子Agent循环警告等 JSON）→ 渲染为 note 段
      try {
        const parsed = JSON.parse(evt.content);
        if (parsed && (parsed.type === 'loop_warning' || parsed.type === 'tool_fail_guard')) {
          const seg: Segment = { type: 'note', content: parsed.message || evt.content };
          if (last && last.role === 'ai') {
            next[lastIdx] = { ...last, segments: [...last.segments, seg] };
          } else {
            next.push({ role: 'ai', segments: [seg] });
          }
        }
      } catch {
        // 非 JSON context 忽略
      }
      break;
    }
    case 'auto_confirm':
      break;
    case 'note': {
      // 直接到达的 note 段（预留）
      const seg: Segment = { type: 'note', content: evt.content };
      if (last && last.role === 'ai') {
        next[lastIdx] = { ...last, segments: [...last.segments, seg] };
      } else {
        next.push({ role: 'ai', segments: [seg] });
      }
      break;
    }
    default:
      break;
  }
  return next;
}

// ============ 消息渲染 ============
function FoldBlock({ title, children, className, loading, defaultOpen }: {
  title: string; children: string; className?: string; loading?: boolean; defaultOpen?: boolean;
}) {
  return (
    <details className={`fold ${className || ''} ${loading ? 'loading' : ''}`} open={loading || defaultOpen}>
      <summary>
        {loading && <span className="spinner-small" />}
        {title}
      </summary>
      <div className="fold-body md-content" dangerouslySetInnerHTML={{ __html: md(children || '') }} />
    </details>
  );
}

// 共享记录本条目卡片：让并行子 Agent 登记的结论在对话流中即时可见。
// 不可折叠、不带展开态 —— 黑板条目是简短结论，藏起来就失去「一眼看到同伴进展」的意义。
const BB_META: Record<string, { icon: string; label: string }> = {
  finding: { icon: '🔍', label: '发现' },
  risk: { icon: '⚠️', label: '风险' },
  conclusion: { icon: '✅', label: '结论' },
  note: { icon: '📝', label: '记录' },
};

function BlackboardCard({ seg }: { seg: Segment }) {
  const meta = BB_META[seg.bbType || 'note'] || BB_META.note;
  return (
    <div className={`blackboard-card bb-${seg.bbType || 'note'}`}>
      <div className="bb-head">
        <span className="bb-icon">{meta.icon}</span>
        <span className="bb-label">记录本 · {meta.label}</span>
        {seg.bbSeq !== undefined && <span className="bb-seq">#{seg.bbSeq}</span>}
        <span className="bb-author">{seg.bbAuthor}</span>
      </div>
      <div className="bb-body md-content" dangerouslySetInnerHTML={{ __html: md(seg.content || '') }} />
    </div>
  );
}

// 结构化工具调用卡片：名称 + 状态点 + 描述副标题 + 可折叠参数 / 输出
function ToolCallCard({ seg }: { seg: Segment }) {
  const st = toolStateOf(seg.result);
  const open = !!seg.running || !seg.result;
  const hasArgs = !!seg.args && seg.args !== '(无参数)';
  const desc = shortDesc(toolDescCache.get(seg.name || '') || '');
  const badge = seg.running
    ? '执行中'
    : st === 'error'
      ? '失败'
      : st === 'empty'
        ? '空结果'
        : '完成';
  return (
    <details className={`tool-call ${seg.running ? 'is-running' : ''}`} open={open}>
      <summary className="tool-head">
        {seg.running ? (
          <span className="spinner-tiny" />
        ) : (
          <span className={`tool-dot ${st || 'done'}`} />
        )}
        <span className="tool-name">{seg.name}</span>
        {desc && <span className="tool-desc">{desc}</span>}
        <span className={`tool-badge ${st || 'done'}`}>{badge}</span>
      </summary>
      {hasArgs && (
        <div className="tool-section">
          <div className="tool-section-head">参数</div>
          <pre className="tool-pre">{prettyJson(seg.args || '')}</pre>
        </div>
      )}
      {seg.result && (
        <div className="tool-section">
          <div className="tool-section-head">输出</div>
          <pre className={`tool-pre tool-result-${st || 'done'}`}>{seg.result}</pre>
        </div>
      )}
    </details>
  );
}

/** 耗时格式化：ms → "1.2s" / "1m05s" */
function fmtDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '';
  if (ms < 1000) return `${ms}ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)}s`;
  const m = Math.floor(s / 60);
  return `${m}m${String(Math.floor(s % 60)).padStart(2, '0')}s`;
}

/** 子 Agent 输出里的失败信号（后端 EXCEED_MAX_ITERS / 异常会带这些字样） */
function subagentStateOf(seg: Segment): 'running' | 'error' | 'done' {
  if (seg.running) return 'running';
  const c = seg.content || '';
  const steps = seg.steps || [];
  // 有工具调用但无正文，仍算完成（有些子 Agent 只做副作用）；完全无步骤才是异常
  if (!c.trim() && steps.length === 0) return 'error';
  // 只认后端固定文案，不做行首 ❌/⚠️ 锚定：评审/清单类子 Agent 正文里经常出现「⚠️ 注意…」
  // 这类正常内容，按符号判失败会把成功的调用误标红。
  // 后端文案：子 Agent「已达迭代上限（x/y 步）」/ 主 Agent「已达到迭代上限（x/y 步）」，故「到」可选。
  if (/已达(?:到)?迭代上限|执行失败|EXCEED_MAX_ITERS/.test(c)) return 'error';
  return 'done';
}

/** 子 Agent 内部单步渲染：思考 / 工具调用 折叠，正文直出 */
function SubStepView({ step }: { step: SubStep }) {
  const [open, setOpen] = useState(false);
  if (step.kind === 'text') {
    const c = step.content || '';
    if (!c.trim()) return null;
    return <div className="sa-step-text md-content" dangerouslySetInnerHTML={{ __html: md(c) }} />;
  }
  if (step.kind === 'reasoning') {
    const c = step.content || '';
    if (!c.trim()) return null;
    return (
      <div className="sa-step">
        <div className="sa-step-head" onClick={() => setOpen((v) => !v)}>
          <span>{open ? '▾' : '▸'} 🧠 思考</span>
          <span className="sa-step-meta">{c.trim().length} 字</span>
        </div>
        {open && <div className="sa-step-body">{c}</div>}
      </div>
    );
  }
  // 工具调用
  const isErr = (step.state || '').toUpperCase().includes('ERROR');
  return (
    <div className={`sa-step tool ${isErr ? 'err' : ''}`}>
      <div className="sa-step-head" onClick={() => setOpen((v) => !v)}>
        <span>{open ? '▾' : '▸'} 🔧 {step.name || '工具'}</span>
        <span className="sa-step-meta">
          {step.running ? <span className="spinner-small" /> : isErr ? '失败' : (step.state || '完成')}
        </span>
      </div>
      {open && (
        <div className="sa-step-body">
          {step.args ? <><b>入参</b>{'\n'}{prettyArgs(step.args)}{'\n\n'}</> : null}
          {step.result ? <><b>结果</b>{'\n'}{step.result}</> : (!step.running && '（无输出）')}
        </div>
      )}
    </div>
  );
}

/** 工具入参尽量格式化为可读 JSON，失败则原样返回 */
function prettyArgs(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

/**
 * 逐 step 比较两个 steps 列表，供 TimelineNode / SubagentGroup 的 memo 比较器复用。
 * 必须逐项比而不能只比尾项：appendSubStep 的 toolArgs / toolResult 分支是「向后扫描找最近一个
 * 仍在 running 的 tool step」，子 Agent 层没有 toolCallId 可用，一次回复里连续起两个工具时
 * 回填命中的往往是非尾项，只比尾项会漏刷新。args 也必须比 —— 否则工具入参在流式过程中不刷新，
 * 一直等到 result 到达才整体跳出来。steps 量级是十位数，O(steps) 开销可忽略。
 */
function sameSteps(as: SubStep[], bs: SubStep[]): boolean {
  if (as.length !== bs.length) return false;
  for (let i = 0; i < as.length; i++) {
    const x = as[i], y = bs[i];
    if (x.kind !== y.kind || x.name !== y.name || x.state !== y.state
        || x.running !== y.running || x.result !== y.result) return false;
    if ((x.content || '').length !== (y.content || '').length) return false;
    if ((x.args || '').length !== (y.args || '').length) return false;
  }
  return true;
}

/** 段自身的可见字段是否相同（steps 另由 sameSteps 判定） */
function sameSegMeta(a: Segment, b: Segment): boolean {
  return a.running === b.running && a.content === b.content
    && a.name === b.name && a.endedAt === b.endedAt;
}

/**
 * 时间线单节点：一次子 Agent 调用。
 * 默认展开运行中的节点、折叠已完成的，避免多个子 Agent 的输出堆成一片。
 */
const TimelineNode = memo(function TimelineNode({ seg, index }: { seg: Segment; index: number }) {
  const state = subagentStateOf(seg);
  // 运行中默认展开（要能看到实时输出）；结束后默认收起，由用户按需展开
  const [open, setOpen] = useState(state === 'running');
  const wasRunning = useRef(state === 'running');
  useEffect(() => {
    // 从 running → 结束的瞬间自动收起；用户手动开合后不再被覆盖
    if (wasRunning.current && state !== 'running') {
      wasRunning.current = false;
      setOpen(false);
    }
    if (state === 'running') wasRunning.current = true;
  }, [state]);

  const dur = seg.endedAt && seg.startedAt ? fmtDuration(seg.endedAt - seg.startedAt) : '';
  // 历史回放的消息只有聚合后的 content、没有 steps：退化成单个正文步，避免误显示「未返回内容」
  const steps: SubStep[] = (seg.steps && seg.steps.length > 0)
    ? seg.steps
    : (seg.content || '').trim() ? [{ kind: 'text', content: seg.content }] : [];
  const toolCount = steps.filter((s) => s.kind === 'tool').length;
  const body = seg.content || '';
  const meta = state === 'running'
    ? '执行中…'
    : state === 'error' ? (body.trim() ? '异常' : '无输出')
      : [toolCount ? `${toolCount} 次工具` : '', dur].filter(Boolean).join(' · ');

  return (
    <div className={`sa-node ${state}`}>
      <div className="sa-node-head" onClick={() => setOpen((v) => !v)}>
        <span className="sa-node-title">
          {open ? '▾' : '▸'} {index}. {seg.name || '子 Agent'}
        </span>
        <span className="sa-node-meta">{meta}</span>
      </div>
      {/* 折叠时真卸载：避免已完成节点的 steps 在每帧流式重渲染中继续参与 md() 解析 */}
      {open && (
        <div className="sa-node-body">
          {steps.length > 0
            ? steps.map((s, i) => <SubStepView key={i} step={s} />)
            : <span style={{ opacity: 0.6 }}>（该子 Agent 未返回内容）</span>}
          {state === 'running' && <span className="sa-typing" />}
        </div>
      )}
    </div>
  );
}, (prev, next) => {
  if (prev.index !== next.index) return false;
  if (!sameSegMeta(prev.seg, next.seg)) return false;
  return sameSteps(prev.seg.steps || [], next.seg.steps || []);
});

/** 子 Agent 分组：把连续的多次子 Agent 调用收进一条时间线 */
const SubagentGroup = memo(function SubagentGroup({ segs }: { segs: Segment[] }) {
  const [open, setOpen] = useState(true);
  const running = segs.some((s) => s.running);
  const doneCount = segs.filter((s) => !s.running).length;
  return (
    <div className="sa-group">
      <div className="sa-group-head" onClick={() => setOpen((v) => !v)}>
        {running && <span className="spinner-small" />}
        <span>{open ? '▾' : '▸'} 🤖 子 Agent 执行</span>
        <span className="sa-group-badge">{doneCount}/{segs.length}</span>
      </div>
      {open && (
        <div className="sa-timeline">
          {segs.map((s, i) => <TimelineNode key={i} seg={s} index={i + 1} />)}
        </div>
      )}
    </div>
  );
}, (prev, next) => {
  const as = prev.segs, bs = next.segs;
  if (as.length !== bs.length) return false;
  for (let i = 0; i < as.length; i++) {
    if (!sameSegMeta(as[i], bs[i])) return false;
    if (!sameSteps(as[i].steps || [], bs[i].steps || [])) return false;
  }
  return true;
});

/** 把相邻的 subagent 段合并成组，其余段原样保留，供渲染层按块输出 */
type RenderBlock = { kind: 'seg'; seg: Segment; i: number } | { kind: 'sa'; segs: Segment[]; i: number };

function groupSegments(segments: Segment[]): RenderBlock[] {
  const out: RenderBlock[] = [];
  for (let i = 0; i < segments.length; i++) {
    const s = segments[i];
    if (s.type === 'subagent') {
      const last = out[out.length - 1];
      if (last && last.kind === 'sa') last.segs.push(s);
      else out.push({ kind: 'sa', segs: [s], i });
    } else {
      out.push({ kind: 'seg', seg: s, i });
    }
  }
  return out;
}

const AiMessage = memo(function AiMessage({ msg, isStreaming, agentLabel }: {
  msg: ChatMessage; isStreaming?: boolean; agentLabel?: string;
}) {
  const label = agentLabel || 'AI';
  // 末段正文位置：仅此处显示打字光标
  const lastTextIdx = msg.segments.reduce((acc, s, idx) => (s.type === 'text' ? idx : acc), -1);
  // 分组只依赖 segments 引用；流式期间 AiMessage 每帧重渲染，缓存后子组件 props 才能保持稳定引用，
  // 否则 SubagentGroup 的 memo 每次都会拿到新的 segs 数组（引用变化不影响其自定义比较器，但省一次分组开销）。
  const blocks = useMemo(() => groupSegments(msg.segments), [msg.segments]);
  return (
    <div className="chat-block ai">
      <div className="chat-meta">🤖 {label}</div>
      <div className="chat-row">
        <div className="chat-avatar">🤖</div>
        <div className="chat-bubble">
          {blocks.map((blk) => {
            if (blk.kind === 'sa') {
              return <SubagentGroup key={`sa-${blk.i}`} segs={blk.segs} />;
            }
            const seg = blk.seg;
            const i = blk.i;
            switch (seg.type) {
              case 'text':
                return (
                  <div key={i} className="md-content-wrap">
                    <div className="md-content" dangerouslySetInnerHTML={{ __html: md(seg.content || '') }} />
                    {isStreaming && i === lastTextIdx && <span className="typing-cursor" />}
                  </div>
                );
              case 'reasoning':
                return <FoldBlock key={i} title="🧠 思考过程" className="reasoning">{seg.content || ''}</FoldBlock>;
              case 'tool':
                return <ToolCallCard key={i} seg={seg} />;
              case 'note':
                return <div key={i} className="system-note">⚠️ {seg.content}</div>;
              case 'blackboard':
                return <BlackboardCard key={i} seg={seg} />;
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
  if (prev.msg.segments.length !== next.msg.segments.length) return false;
  for (let i = 0; i < prev.msg.segments.length; i++) {
    const a = prev.msg.segments[i], b = next.msg.segments[i];
    if (a.type !== b.type || a.content !== b.content || a.name !== b.name
        || a.args !== b.args || a.result !== b.result || a.running !== b.running
        || a.endedAt !== b.endedAt
        || a.bbSeq !== b.bbSeq || a.bbType !== b.bbType || a.bbAuthor !== b.bbAuthor) return false;
    // 子 Agent 内部步骤是流式追加的，须逐步比较否则时间线不刷新
    const as = a.steps || [];
    const bs = b.steps || [];
    if (as.length !== bs.length) return false;
    for (let k = 0; k < as.length; k++) {
      const x = as[k];
      const y = bs[k];
      if (x.kind !== y.kind || x.content !== y.content || x.name !== y.name
          || x.args !== y.args || x.result !== y.result || x.running !== y.running) return false;
    }
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
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameDraft, setRenameDraft] = useState('');
  const [sessionId, setSessionId] = useState('');
  const [input, setInput] = useState('');
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [tab, setTab] = useState<'sessions' | 'auth' | 'files' | 'tools'>('sessions');
  const [statusHint, setStatusHint] = useState('');
  const [authList, setAuthList] = useState<{ id: number; toolName: string; createdAt: string }[]>([]);
  const [authOptions, setAuthOptions] = useState<{ name: string; displayName: string; requiresConfirm: boolean }[]>([]);
  const [toolCatalog, setToolCatalog] = useState<ToolDef[]>([]);
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [filePath, setFilePath] = useState('');
  interface OpenFile { entry: FileEntry; kind: 'text' | 'image'; content?: string; error?: string; loading: boolean; }
  const [openFiles, setOpenFiles] = useState<OpenFile[]>([]);
  // openFiles 的 ref 镜像：file_changed 的防抖回调在闭包外执行，需要读到最新的打开列表
  const openFilesRef = useRef<OpenFile[]>([]);
  useEffect(() => { openFilesRef.current = openFiles; }, [openFiles]);
  const [activeFileTab, setActiveFileTab] = useState<string | null>(null);
  const [panelWidth, setPanelWidth] = useState(320);
  const [resizing, setResizing] = useState(false);
  const [skillName, setSkillName] = useState<string>('');
  const [availableSkills, setAvailableSkills] = useState<{ name: string; description: string; scope: string }[]>([]);
  // /api/skills 是否已给出结论（成功或失败都算）。用来区分"候选集未知"与"已知为空"，
  // 只有已知时才允许清理 skillName —— 否则请求未回的窗口期会静默抹掉用户的选择。
  const [skillsLoaded, setSkillsLoaded] = useState(false);
  // 当前工作区激活的场景（场景编排页激活后在此显示）
  // skills：场景绑定的 skill 白名单原文（JSON 数组或逗号分隔），用于收窄下方 Skill 下拉候选
  const [scenario, setScenario] = useState<{ icon: string; displayName: string; name: string; mode: string; skills?: string | null } | null>(null);
  /**
   * Skill 下拉的可见候选：用激活场景绑定的 skill 白名单收窄 availableSkills（全量）。
   * <p>场景绑定是软约束/推荐，只影响 UI 候选，不做发送前拦截，后端语义不变。
   * <ul>
   *   <li>白名单为空（未绑定）→ 全量；</li>
   *   <li>有交集 → 按白名单顺序取交集（顺序体现场景作者的推荐次序）；</li>
   *   <li>交集为空（白名单里的 skill 已被删/改名）→ 回退全量。否则下拉整体消失，
   *       用户既选不了 skill 也看不出原因。</li>
   * </ul>
   */
  const skillCandidates = useMemo(() => {
    const whitelist = parseNames(scenario?.skills);
    if (whitelist.length === 0) return { list: availableSkills, narrowed: false, stale: false };
    // 同名 skill 可能同时存在于 global / workspace 两级，两条都保留（option key 含 scope，不冲突）
    const picked = whitelist.flatMap((n) => availableSkills.filter((s) => s.name === n));
    if (picked.length === 0) return { list: availableSkills, narrowed: false, stale: true };
    return { list: picked, narrowed: true, stale: false };
  }, [availableSkills, scenario?.skills]);
  const visibleSkills = skillCandidates.list;
  // 已选 skill 被收窄掉（切换场景 / skill 被删）时静默清空，避免提交一个下拉里看不见的值
  useEffect(() => {
    // 候选集未加载完时不做清理判断：availableSkills 为空既可能是"还没回"，
    // 也可能是"确实没有"，后者必须清（下拉已隐藏，用户改不了残值），前者绝不能清。
    // 用 availableSkills.length 无法区分这两种情况，只能靠显式的加载标志。
    if (!skillsLoaded) return;
    if (skillName && !visibleSkills.some((s) => s.name === skillName)) setSkillName('');
  }, [skillsLoaded, visibleSkills, skillName]);
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
  // 用户点击「停止」的时刻。后端 interrupt 到 Agent 真正退出之间有延迟，
  // 管道里的残留事件仍会到达；这段窗口内不允许数据事件把 running 复活，
  // 否则前端刚复位又被打回 running（按钮在「停止/发送」间闪烁）。
  const stoppedAtRef = useRef(0);
  const STOP_GRACE_MS = 3000;
  // 文件变更刷新的防抖窗口：Agent 分段写大文件时会连续推多个 file_changed，
  // 合并到同一窗口内只做一次刷新。250ms 兼顾「感知上即时」与「请求不放大」。
  const FILE_REFRESH_DEBOUNCE_MS = 250;
  // 复制成功后 ✓ 图标的显示时长
  const COPY_FEEDBACK_MS = 1500;
  const pendingEvtsRef = useRef<StreamEvent[]>([]);
  // file_changed 处理器的 ref 转发：实现定义在文件面板逻辑处（在 handleWsEvent 之后），
  // 用 ref 打破声明顺序依赖，避免为此把整块文件逻辑上移。
  const onFileChangedRef = useRef<(path: string) => void>(() => {});
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
      // 刚点过停止：忽略中断生效前管道里的残留事件，不把 running 复活
      const justStopped = Date.now() - stoppedAtRef.current < STOP_GRACE_MS;
      if (!cur.running && !justStopped) {
        patch({ running: true, error: '' });
      }
    }
    // 回合终止兜底：正常路径靠 tool_end / subagent_end 复位；end/error 时强制把
    // 所有仍标记 running 的工具/子 Agent 段置为完成，避免卡片永久卡"执行中"
    const settleRunningSegments = () => {
      updateChatSession(key, (c) => {
        let changed = false;
        const messages = c.messages.map((m: ChatMessage) => {
          if (!m.segments.some((sg: Segment) => sg.running)) return m;
          changed = true;
          return {
            ...m,
            segments: m.segments.map((sg: Segment) => (sg.running ? { ...sg, running: false } : sg)),
          };
        });
        return changed ? { ...c, messages } : c;
      });
    };
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
      // 工具执行结束：从活跃集合移除 + push 到渲染队列（reduceMessage 设置 running=false）
      updateChatSession(key, (c) => ({
        ...c,
        activeTools: c.activeTools.filter((t: string) => t !== evt.content),
      }));
      flushNow();
      pendingEvtsRef.current.push(evt);
      scheduleFlush();
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
      // 子 Agent 结束：从活跃集合移除 + push 到渲染队列（reduceMessage 设置 running=false）
      updateChatSession(key, (c) => ({
        ...c,
        activeSubagents: c.activeSubagents.filter((s: string) => s !== evt.content),
      }));
      flushNow();
      pendingEvtsRef.current.push(evt);
      scheduleFlush();
    } else if (evt.type === 'file_changed') {
      // 工作区文件被写类工具改动：不进消息流，只触发文件面板刷新（副作用，非渲染数据）
      onFileChangedRef.current(evt.content);
    } else if (evt.type === 'blackboard') {
      // 共享记录本新增条目：进消息流按时序渲染成卡片，让并行子 Agent 的结论对用户可见。
      // 走统一批量队列而非立即 setState —— 与 text/tool 共用 32ms 合批，保证时序不乱。
      pendingEvtsRef.current.push(evt);
      scheduleFlush();
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
      // 出错时复位 running，避免 UI 卡在"运行中"且无后续事件
      patch({ error: evt.content, running: false });
      settleRunningSegments();
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
        // 刚点过停止：后端 sessionDisposables 的清理与 Agent 实际退出存在时间差，
        // 此时回推的 running=true 是过期状态，不能覆盖本地已停止的结果
        const justStopped = Date.now() - stoppedAtRef.current < STOP_GRACE_MS;
        if (cur.running !== nextRunning && !(nextRunning && justStopped)) {
          updates.running = nextRunning;
        }
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
      settleRunningSegments();
      if (hangTimerRef.current) {
        clearInterval(hangTimerRef.current);
        hangTimerRef.current = null;
      }
      // 队列自动发送（介入插队到队首的消息也会被 sendNextQueued 取到）
      setTimeout(() => sendNextQueued(), 0);
    } else if (evt.type === 'stopped') {
      // 用户主动停止的确认回执：只复位 UI，不 flush 队列（否则与「停止」语义相反）。
      // 点击时前端已本地复位过一次，这里是后端确认 + 覆盖 WS 抖动丢事件的兜底。
      flushNow();
      stoppedAtRef.current = Date.now();
      patch({ running: false, activeTools: [], activeSubagents: [] });
      settleRunningSegments();
      if (hangTimerRef.current) {
        clearInterval(hangTimerRef.current);
        hangTimerRef.current = null;
      }
      console.log('[ws] stopped 已确认，队列保留不自动发送');
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
    // 切换工作区：候选集重新变为"未知"，并清掉上一个工作区选中的 skill
    // （skill 是按 workspaceId 拉的，A 工作区的选择不该串到 B 工作区）。
    setSkillsLoaded(false);
    setSkillName('');
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
        loadTools();
        loadFiles(workspaceId, '');
        loadSkills(workspaceId);
        // 激活场景徽标（未激活返回 null）
        getJson<{ icon: string; displayName: string; name: string; mode: string; skills?: string | null } | null>(
          `/api/scenarios/active/${workspaceId}`,
        ).then(setScenario).catch(() => setScenario(null));
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
        } else if (b.type === 'AI_TEXT' || b.type === 'THINKING' || b.type === 'TOOL_CALL' || b.type === 'TOOL_RESULT' || b.type === 'SUBAGENT' || b.type === 'BLACKBOARD') {
          if (!cur) {
            cur = { role: 'ai', segments: [] };
            msgs.push(cur);
          }
          if (b.type === 'AI_TEXT') cur.segments.push({ type: 'text', content: b.content });
          else if (b.type === 'THINKING') cur.segments.push({ type: 'reasoning', content: b.content });
          else if (b.type === 'TOOL_CALL') {
            cur.segments.push({ type: 'tool', name: b.toolName, args: b.toolArgs || '', result: '', running: false });
          } else if (b.type === 'TOOL_RESULT') {
            // 配对到最近的 tool 段（历史里工具调用与结果分两条，原逻辑会丢弃结果）
            const lastTool = cur.segments[cur.segments.length - 1];
            if (lastTool && lastTool.type === 'tool') {
              lastTool.result = b.toolResult || '';
            } else {
              cur.segments.push({ type: 'tool', name: b.toolName, args: '', result: b.toolResult || '', running: false });
            }
          } else if (b.type === 'BLACKBOARD') {
            // content 存的是 emit 时的 payload JSON，与实时通道同构
            let p: { seq?: number; type?: string; author?: string; content?: string } = {};
            try {
              p = JSON.parse(b.content || '{}');
            } catch {
              p = { content: b.content };
            }
            cur.segments.push({
              type: 'blackboard',
              content: p.content || '',
              bbSeq: p.seq,
              bbType: p.type || 'note',
              bbAuthor: p.author || 'unknown',
            });
          } else {
            // 历史落盘的 subagent 只有聚合正文（TranscriptRecorder 已把步骤降级为纯文本，
            // 工具入参根本没落盘），故 steps 只能给空数组：由 TimelineNode 退化成单个正文步。
            // 显式给出 running/steps 以对齐 Segment 类型契约，避免 undefined 让节点误判为运行中。
            cur.segments.push({ type: 'subagent', name: b.subagentName || '', content: b.content, running: false, steps: [] });
          }
        }
      }
      // 重连触发的 loadHistory 可能与正在进行的流式输出撞车：历史里的 subagent 是塌成纯文本的，
      // 一旦覆盖就会把正在生长的时间线（steps）整块抹平。因此运行中且已有 running 段时放弃覆盖，
      // 后续 WS 增量会继续追加到现有段上。仅同步运行状态。
      // 取「最后一条 ai 消息」而非最后一条消息：运行期间队列可能已在尾部追加了新的 user 消息，
      // 若只看尾项就会漏判、时间线仍被压平。也不能扫全部消息 —— 历史里残留的 stale running 段
      // 会永久阻塞历史刷新。
      const curSession = getChatSession(chatKey(wid, sid));
      let lastAi: (typeof curSession.messages)[number] | null = null;
      for (let i = curSession.messages.length - 1; i >= 0; i--) {
        if (curSession.messages[i].role === 'ai') { lastAi = curSession.messages[i]; break; }
      }
      const hasRunningSeg = !!lastAi && lastAi.segments.some((s) => s.running);
      if (curSession.running && hasRunningSeg) {
        // tsconfig 未引入 vite/client 类型，import.meta.env 无声明，这里做结构化收窄而非改配置
        if ((import.meta as { env?: { DEV?: boolean } }).env?.DEV) {
          console.log('[history] 会话运行中且存在 running 段，跳过历史覆盖以保留时间线结构');
        }
        syncSessionStatus(wid, sid);
        return;
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
        getJson<{ name: string; displayName: string; requiresConfirm: boolean }[]>('/api/tools/builtin'),
      ]);
      setAuthList(rules);
      // 只保留「调用时会弹确认」的工具：静默放行的只读工具（read_file/grep_files 等）
      // 摆在白名单里是噪声 —— 它们本就不询问，点开关不会改变任何行为。
      // requiresConfirm 由后端 ToolPermissionPolicy 判定，与权限上下文同源，不会漂移。
      setAuthOptions(tools.filter((t) => t.requiresConfirm));
    } catch {
      // 忽略
    }
  };

  /** 加载工具目录：填充侧栏列表 + ToolCallCard 描述缓存 */
  const loadTools = async () => {
    try {
      const all = await getJson<ToolDef[]>('/api/tools/builtin');
      setToolCatalog(all);
      for (const t of all) {
        toolDescCache.set(t.name, t.description || t.displayName || '');
      }
    } catch {
      // 忽略
    }
  };

  const loadSkills = async (wid: string) => {
    try {
      const all = await getJson<{ scope: string; name: string; description: string }[]>(
        `/api/skills?workspaceId=${encodeURIComponent(wid)}`);
      // 排除 *-subagent（global-subagent / workspace-subagent）—— 那是子 Agent 声明文件，
      // 与 skill 同接口返回、靠 scope 区分，混进来会让用户把子 Agent 当 skill 加载。
      // 这里保留全量 skill，场景白名单的收窄在 visibleSkills 里做（避免两个并发请求的时序竞态）。
      setAvailableSkills(all.filter(s => !String(s.scope || '').endsWith('-subagent')));
    } catch {
      setAvailableSkills([]);
    } finally {
      // 失败也置 true：拿不到列表等同于"已知为空"，下拉会隐藏，
      // 此时必须允许清理 skillName，否则会提交一个用户看不见也改不了的残值。
      setSkillsLoaded(true);
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

  // ---- 文件实时刷新（file_changed 事件驱动）----
  // 待刷新路径缓冲：Agent 连续写同一文件会高频推事件，合并到一个时间窗口内统一处理，
  // 避免每次写入都触发一轮 fetch（否则长文档分段追加会打出几十个请求）。
  const pendingFileChangesRef = useRef<Set<string>>(new Set());
  const fileRefreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 重新拉取单个已打开文本文件的内容。
  // 带 cache-buster：file-content 对图片设了 max-age=600，不加会命中浏览器缓存拿到旧内容。
  const reloadOpenFile = useCallback(async (path: string) => {
    if (!workspaceId) return;
    try {
      const res = await fetch(
        `/api/workspaces/${workspaceId}/file-content?path=${encodeURIComponent(path)}&t=${Date.now()}`,
        { cache: 'no-store' },
      );
      if (!res.ok) return;
      const text = await res.text();
      setOpenFiles((prev) => prev.map((f) => (
        f.entry.path === path ? { ...f, content: text, error: undefined } : f
      )));
    } catch {
      // 静默失败：实时刷新是增强能力，网络抖动不该弹错误打断用户
    }
  }, [workspaceId]);

  // 图片无法用 fetch 替换内容，只能靠改 src 上的版本号强制重新解码
  const [imageVersion, setImageVersion] = useState(0);

  const flushFileChanges = useCallback(() => {
    const changed = Array.from(pendingFileChangesRef.current);
    pendingFileChangesRef.current.clear();
    if (changed.length === 0 || !workspaceId) return;

    // 空路径 = 后端说"文件变了但不知道是哪个"（shell 类工具）。
    // 用户必然处在某个目录、打开着某几个文件，刷新这些即可，无需后端给出精确路径。
    const unknownPath = changed.some((p) => p === '');

    // 1) 文件树：只要有变更就刷新当前目录（新增/删除文件需要重新列举）
    loadFiles(workspaceId, filePath);

    // 2) 已打开的标签页：路径已知时只重拉受影响的，未知时全部重拉
    const openPaths = new Set(openFilesRef.current.map((f) => f.entry.path));
    let touchedImage = false;
    const targets = unknownPath
      ? openFilesRef.current.map((f) => f.entry.path)
      : changed.filter((p) => openPaths.has(p));
    for (const p of targets) {
      const target = openFilesRef.current.find((f) => f.entry.path === p);
      if (!target) continue;
      if (target.kind === 'text') {
        reloadOpenFile(p);
      } else if (target.kind === 'image') {
        touchedImage = true;
      }
    }
    if (touchedImage) setImageVersion((v) => v + 1);
  }, [workspaceId, filePath, reloadOpenFile]);

  const flushFileChangesRef = useRef(flushFileChanges);
  useEffect(() => {
    flushFileChangesRef.current = flushFileChanges;
  }, [flushFileChanges]);

  const onFileChanged = useCallback((path: string) => {
    pendingFileChangesRef.current.add(path);
    if (fileRefreshTimerRef.current) return;
    fileRefreshTimerRef.current = setTimeout(() => {
      fileRefreshTimerRef.current = null;
      flushFileChangesRef.current();
    }, FILE_REFRESH_DEBOUNCE_MS);
  }, []);

  // 卸载时清理定时器，避免组件销毁后仍触发 setState
  useEffect(() => () => {
    if (fileRefreshTimerRef.current) clearTimeout(fileRefreshTimerRef.current);
  }, []);

  useEffect(() => {
    onFileChangedRef.current = onFileChanged;
  }, [onFileChanged]);

  // ---- 复制文件路径 ----
  // 复制成功的路径（用于短暂显示 ✓ 反馈）
  const [copiedPath, setCopiedPath] = useState<string | null>(null);
  const copyResetTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // navigator.clipboard 只在 HTTPS 或 localhost 可用；局域网 http 访问时需要
  // textarea + execCommand 兜底，否则复制会静默失效。
  const writeClipboard = async (text: string): Promise<boolean> => {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        return true;
      }
    } catch {
      // 落到兜底方案
    }
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand('copy');
      document.body.removeChild(ta);
      return ok;
    } catch {
      return false;
    }
  };

  const copyFilePath = useCallback(async (path: string) => {
    const ok = await writeClipboard(path);
    if (!ok) {
      patch({ error: '复制失败，请手动选择路径复制' });
      return;
    }
    setCopiedPath(path);
    if (copyResetTimerRef.current) clearTimeout(copyResetTimerRef.current);
    copyResetTimerRef.current = setTimeout(() => setCopiedPath(null), COPY_FEEDBACK_MS);
  }, [patch]);

  useEffect(() => () => {
    if (copyResetTimerRef.current) clearTimeout(copyResetTimerRef.current);
  }, []);

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

  // 会话重命名：双击标题或点击 ✎ 进入行内编辑
  const startRename = (s: SessionItem) => {
    setRenamingId(s.id);
    setRenameDraft(s.title);
  };

  const cancelRename = () => {
    setRenamingId(null);
    setRenameDraft('');
  };

  const commitRename = async () => {
    if (!renamingId || !workspaceId) return;
    const sid = renamingId;
    const title = renameDraft.trim();
    const original = sessions.find((s) => s.id === sid);
    // 空标题或未改动：直接退出编辑，不打无意义的请求
    if (!title || !original || title === original.title) {
      cancelRename();
      return;
    }
    // 乐观更新，请求失败再回滚，避免输入框闪烁
    setSessions((prev) => prev.map((s) => (s.id === sid ? { ...s, title } : s)));
    cancelRename();
    try {
      await putJson<SessionItem>(`/api/workspaces/${workspaceId}/sessions/${sid}`, { title });
    } catch (e) {
      setSessions((prev) => prev.map((s) => (s.id === sid ? { ...s, title: original.title } : s)));
      alert(`重命名失败：${e instanceof Error ? e.message : String(e)}`);
    }
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
        const raw = String(reader.result);
        const b64 = raw.split(',')[1];
        setAttachments((prev) => [...prev, { name: f.name, mimeType: resolveMimeType(f, raw), base64Data: b64 }]);
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

  // 介入当前轮次：把插话实时注入正在执行的回合（不中断 Agent）
  //
  // 【为什么必须发后端】此前这里只把消息重排到本地队列队首，后端全然不知，
  // 表现为「点了介入什么都没发生」——真正的介入需要走 WS intervene，
  // 由后端 inboxPush 投进会话收件箱，InboxMiddleware 在下一个推理步前
  // 排空成 HintBlock 注入当前上下文，模型才会在本轮内看到并响应。
  const interveneNow = useCallback(() => {
    if (!workspaceId || !sessionId || !running) return;
    const text = input.trim();
    if (!text) return;
    getChatSocket().send({ type: 'intervene', workspaceId, sessionId, content: text });
    setInput('');
    setAttachments([]);
    // 本地即时回显，让用户看到自己的插话已生效（后端不回推用户消息）
    updateChatSession(key, (c) => ({
      ...c,
      messages: [
        ...c.messages,
        { role: 'user' as const, segments: [{ type: 'text' as const, content: text }] },
      ],
    }));
  }, [workspaceId, sessionId, running, input, key, updateChatSession]);

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
        ✅ 白名单内的工具调用时不再询问确认<br />
        仅列出需要授权的工具；只读工具（读文件、搜索、分析等）本就静默放行，无需配置
      </div>
    </div>
  );

  // 工具目录分组显示名
  const GROUP_LABEL: Record<string, string> = {
    FILE: '📁 文件',
    CODE: '🧑‍💻 代码',
    WEB: '🌐 网络',
    MEMORY: '🧠 记忆',
    SESSION: '💬 会话',
    AGENT: '🤖 多Agent',
    SHELL: '💻 Shell',
    FRAMEWORK: '⚙️ 框架',
    GENERAL: '🔧 通用',
  };

  // 侧栏：工具目录（分组展示全量工具 + 描述，供用户了解能力）
  const toolsPanel = (
    <div className="session-list tools-panel" style={{ padding: '4px 8px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h4 style={{ margin: '0 0 8px' }}>🧰 工具目录 <span className="hint" style={{ fontWeight: 400 }}>（{toolCatalog.length}）</span></h4>
        <button className="btn small" onClick={loadTools}>刷新</button>
      </div>
      {toolCatalog.length === 0 ? (
        <div className="hint">暂无工具数据，点击「刷新」加载</div>
      ) : (
        Array.from(new Set(toolCatalog.map((t) => t.group))).map((group) => (
          <div key={group} className="tools-group">
            <div className="tools-group-head">{GROUP_LABEL[group] || group}</div>
            {toolCatalog.filter((t) => t.group === group).map((t) => (
              <details key={t.name} className="tools-item">
                <summary className="tools-item-head">
                  <span className="tools-item-name">{t.name}</span>
                  <span className="tools-item-display">{t.displayName}</span>
                </summary>
                <div className="tools-item-body">
                  {t.description && <p className="tools-item-desc">{t.description}</p>}
                  {t.params && t.params.length > 0 && (
                    <div className="tools-item-params">
                      {t.params.map((p) => (
                        <div key={p.name} className="tools-item-param">
                          <code>{p.name}</code>
                          {p.required && <em className="req">必填</em>}
                          <span>{p.description}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </details>
            ))}
          </div>
        ))
      )}
      <div className="hint" style={{ marginTop: 8, fontSize: 11 }}>
        💡 工具会在 AI 需要时自动调用；写/执行类操作会先征询你确认
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
              <button
                type="button"
                className="file-entry-copy"
                title="复制相对路径"
                onClick={(e) => {
                  e.stopPropagation(); // 不要连带触发进入目录
                  copyFilePath(filePath ? `${filePath}/${f.name}` : f.name);
                }}
              >
                {copiedPath === (filePath ? `${filePath}/${f.name}` : f.name) ? '✓' : '⧉'}
              </button>
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
                <button
                  type="button"
                  className="file-entry-copy"
                  title="复制相对路径"
                  onClick={(e) => {
                    e.stopPropagation(); // 不要连带触发打开预览
                    copyFilePath(filePath ? `${filePath}/${f.name}` : f.name);
                  }}
                >
                  {copiedPath === (filePath ? `${filePath}/${f.name}` : f.name) ? '✓' : '⧉'}
                </button>
                {canOpen && <span className="file-entry-open">↗</span>}
              </div>
            );
          })}
        </>
      )}
    </div>
  );

  // 空状态建议：点击直接填入输入框（用户可编辑后再发）
  const SUGGESTIONS = [
    '阅读当前工作区，总结项目结构',
    '帮我写一个读取 CSV 并统计的小脚本',
    '检查这个项目有没有明显的代码问题',
    '把这个目录下的图片按月份归类',
  ];
  const useSuggestion = (t: string) => {
    setInput(t);
    requestAnimationFrame(() => textareaRef.current?.focus());
  };

  const chatArea = (
    <div className="chat-main">
      <div className="chat-topbar">
        <span className="ws-name">💬 {workspace?.name}</span>
        {workspace?.agentName && <span className="agent-badge">🤖 {workspace.agentName}</span>}
        {scenario && (
          <span
            className="agent-badge"
            title={scenario.mode === 'team' ? '多智能体编排场景（在场景页管理）' : '单智能体场景（在场景页管理）'}
            style={{cursor: 'pointer'}}
            onClick={() => navigate('/scenarios')}
          >
            {scenario.icon || '🎬'} {scenario.displayName || scenario.name}
            {scenario.mode === 'team' ? ' 🤝' : ''}
          </span>
        )}
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
      </div>
      <div className="chat-scroll" ref={scrollRef} onScroll={(e) => {
        const el = e.currentTarget;
        stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 150;
      }}>
        <div className="chat-messages">
          {messages.length === 0 && !running && (
            <div className="empty">
              <h3>开始对话</h3>
              <p className="hint">AI 只能在你指定的工作区目录内工作。试试下面的提问：</p>
              <div className="suggest-grid">
                {SUGGESTIONS.map((s, i) => (
                  <button key={i} className="suggest-chip" onClick={() => useSuggestion(s)}>{s}</button>
                ))}
              </div>
            </div>
          )}
          {messages.map((m, i) => (
            m.role === 'user'
              ? <UserMessage key={i} msg={m} />
              : <AiMessage key={i} msg={m} isStreaming={running && i === messages.length - 1} agentLabel={workspace?.agentName || workspace?.name} />
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
        <div className="input-row">
          {visibleSkills.length > 0 && (
            <select
              className="skill-select"
              value={skillName}
              onChange={(e) => setSkillName(e.target.value)}
              title={
                skillCandidates.narrowed
                  ? '加载 Skill 作为本轮对话的操作指南（候选已按当前场景收窄）'
                  : skillCandidates.stale
                    ? '加载 Skill 作为本轮对话的操作指南（当前场景绑定的 Skill 均不存在，已回退为全部候选）'
                    : '加载 Skill 作为本轮对话的操作指南'
              }
            >
              <option value="">— 不指定 —</option>
              {visibleSkills.map((s) => (
                <option key={s.scope + ':' + s.name} value={s.name}>
                  {s.name}{s.description ? ` — ${s.description}` : ''}
                </option>
              ))}
            </select>
          )}
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
                // 记录停止时刻：用于门禁中断生效前管道里的残留事件 / 过期 status 回推
                stoppedAtRef.current = Date.now();
                // 本地立即复位，不依赖后端回推 end：
                // 后端 stopChat 会 dispose Flux，之后不再推任何事件；若那条补发的 end
                // 丢失（WS 抖动 / sid 不匹配），前端会永久卡 running=true，
                // 此时 submit() 走「入队」分支 → 消息只进 messageQueue 不发送，
                // 而队列又只在收到 end 时才 flush → 死锁（表现为「发了没反应」）。
                updateChatSession(key, (c) => ({
                  ...c,
                  running: false,
                  activeTools: [],
                  activeSubagents: [],
                  messages: c.messages.map((m: ChatMessage) => (
                    m.segments.some((sg: Segment) => sg.running)
                      ? { ...m, segments: m.segments.map((sg: Segment) => (sg.running ? { ...sg, running: false } : sg)) }
                      : m
                  )),
                }));
                if (hangTimerRef.current) {
                  clearInterval(hangTimerRef.current);
                  hangTimerRef.current = null;
                }
                // 队列策略：保留待发消息但「不」自动发送。
                // 停止是用户的明确中止意图，若此处 flush 队列，会立刻拉起新一轮回复，
                // 与「停止」语义相反。队列在 UI 上仍可见，由用户手动点发送或删除。
                const qLen = getChatSession(key).messageQueue.length;
                setStatusHint(qLen > 0 ? `已停止（${qLen} 条待发已保留，可手动发送）` : '已停止');
                console.log('[ws-send] stop（前端已本地复位 running）, 保留队列 qLen=', qLen);
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
            src={`/api/workspaces/${workspaceId}/file-content?path=${encodeURIComponent(file.entry.path)}&v=${imageVersion}`}
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
            {activeFile ? (
              <>
                <div className="file-path-bar" title="点击复制相对路径">
                  <span className="file-path-text">{activeFile.entry.path}</span>
                  <button
                    type="button"
                    className="file-path-copy"
                    onClick={() => copyFilePath(activeFile.entry.path)}
                  >
                    {copiedPath === activeFile.entry.path ? '✓ 已复制' : '⧉ 复制路径'}
                  </button>
                </div>
                {fileContentView(activeFile)}
              </>
            ) : <div className="hint" style={{ padding: 16 }}>无内容</div>}
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
                  onClick={() => { if (renamingId !== s.id) switchSession(s.id); }}
                >
                  {renamingId === s.id ? (
                    <input
                      className="session-rename-input"
                      value={renameDraft}
                      maxLength={100}
                      autoFocus
                      onClick={(e) => e.stopPropagation()}
                      onChange={(e) => setRenameDraft(e.target.value)}
                      onBlur={commitRename}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') { e.preventDefault(); commitRename(); }
                        else if (e.key === 'Escape') { e.preventDefault(); cancelRename(); }
                      }}
                    />
                  ) : (
                    <>
                      <span
                        className="title"
                        title={`${s.title}（双击重命名）`}
                        onDoubleClick={(e) => { e.stopPropagation(); startRename(s); }}
                      >{s.title}</span>
                      <button
                        className="session-rename"
                        title="重命名"
                        onClick={(e) => { e.stopPropagation(); startRename(s); }}
                      >✎</button>
                      <button
                        className="session-del"
                        title="删除"
                        onClick={(e) => { e.stopPropagation(); deleteSession(s.id); }}
                      >🗑</button>
                    </>
                  )}
                </div>
              ))}
            </div>
          </>
        );
      case 'auth':
        return authPanel;
      case 'tools':
        return toolsPanel;
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
          <button className={`btn small ${tab === 'tools' ? 'primary' : ''}`} onClick={() => { if (toolCatalog.length === 0) loadTools(); setTab('tools'); }}>🧰 工具</button>
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
