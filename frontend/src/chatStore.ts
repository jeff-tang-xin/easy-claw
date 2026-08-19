// 全局聊天状态 store（模块级单例）
// 目的：切换菜单/路由时组件卸载，但进行中的 SSE 流与消息状态必须保持，
// 切回来即恢复。组件只是订阅者，流循环写入 store 不依赖组件生命周期。
//
// localStorage 持久化：
//   ec:activeSessions           —— workspaceId → 上次活跃的 sessionId
//   ec:queue:${workspaceId}|${sessionId}  —— 每会话的 messageQueue
// 其他字段（running / messages / activeTools / pending）不持久化：
//   · messages 由后端 history API 加载
//   · running 由 WS 事件驱动（重连后收到数据事件自动置 true）
//   · activeTools / pending 是瞬时 UI 状态，重连后清空
import {useEffect, useReducer} from 'react';

let msgIdSeed = 0;
export function newMessageId(): string {
  msgIdSeed += 1;
  return `msg-${Date.now()}-${msgIdSeed}`;
}


export interface Segment {
  type: string;
  content: string;
  name?: string;
}

export interface ChatMessage {
  id?: string;
  role: 'user' | 'ai';
  segments: Segment[];
  attachments?: { name: string; mimeType: string }[];
  executionLog?: ExecutionLogEntry[];
  plan?: PlanState;
}

export interface PendingConfirm {
  tools: { name: string; input: string }[];
  raw: string;
}

export interface AttachmentPayload {
  name: string;
  mimeType: string;
  base64Data: string;
}

export interface QueueItem {
  id: string;
  text: string;
  attachments: AttachmentPayload[];
  guides: string[];
}

export interface ChatSessionState {
  messages: ChatMessage[];
  running: boolean;
  pending: PendingConfirm | null;
  error: string;
  activeTools: string[];
  activeSubagents: string[];
  messageQueue: QueueItem[];
}

// ===== Timeline 数据模型 =====

export type ExecStatus = 'running' | 'done' | 'failed';

export interface ExecEntryBase {
  timestamp?: number;
  processId?: string;
  parentProcessId?: string;
  agentName?: string;
  status: ExecStatus;
}

export interface LlmEntry extends ExecEntryBase {
  kind: 'llm';
  action?: string;
  model: string;
  promptPreview?: string;
  messageCount?: number;
  responsePreview?: string;
  responseLength?: number;
  durationMs?: number;
}

export interface ToolEntry extends ExecEntryBase {
  kind: 'tool';
  action?: string;
  tool: string;
  input: string;
  output?: string;
  correlationId?: string;
  durationMs?: number;
}

export interface ActionEntry extends ExecEntryBase {
  kind: 'action';
  name: string;
  description?: string;
  index?: number;
  total?: number;
  durationMs?: number;
}

export interface SubagentEntry extends ExecEntryBase {
  kind: 'subagent';
  name: string;
  lifecycle: 'start' | 'end';
  durationMs?: number;
}

export type ExecutionLogEntry = LlmEntry | ToolEntry | ActionEntry | SubagentEntry;

/**
 * AgentGroup：按 processId 平铺分组的智能体面板数据。
 * 多智能体并行，不区分主子，每个 AgentGroup 是一个独立面板。
 */
export interface AgentGroup {
  processId: string;
  agentName: string;
  parentProcessId?: string;
  entries: ExecutionLogEntry[];
  status: ExecStatus;
  startedAt: number;
  endedAt?: number;
  actionName?: string;
}

/** 扁平 entries → 按 processId 平铺分组（不嵌套，所有智能体平等并列） */
export function groupByProcess(entries: ExecutionLogEntry[]): AgentGroup[] {
  const groups = new Map<string, AgentGroup>();
  const order: string[] = [];

  const getOrCreate = (pid: string): AgentGroup => {
    let g = groups.get(pid);
    if (!g) {
      g = { processId: pid, agentName: '', entries: [], status: 'running', startedAt: Date.now() };
      groups.set(pid, g);
      order.push(pid);
    }
    return g;
  };

  for (const e of entries) {
    const pid = e.processId || '__root__';
    const g = getOrCreate(pid);

    if (e.agentName && !g.agentName) g.agentName = e.agentName;
    if (e.parentProcessId && !g.parentProcessId) g.parentProcessId = e.parentProcessId;
    if (e.timestamp) {
      if (!g.startedAt || e.timestamp < g.startedAt) g.startedAt = e.timestamp;
      if (!g.endedAt || e.timestamp > g.endedAt) g.endedAt = e.timestamp;
    }

    if (e.kind === 'action') {
      if (!g.actionName) g.actionName = e.name;
      if (e.status === 'done' || e.status === 'failed') g.status = e.status;
      g.entries.push(e);
    } else if (e.kind === 'subagent') {
      if (e.lifecycle === 'end') g.status = e.status;
      g.entries.push(e);
    } else {
      g.entries.push(e);
      if (e.status === 'done' || e.status === 'failed') {
        g.status = (g.status === 'running') ? e.status : g.status;
      }
    }
  }

  for (const g of groups.values()) {
    if (!g.agentName) g.agentName = g.actionName || g.processId;
    g.entries.sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0));
      // 只要还有 running 条目，就不能显示为“完成”
      const hasRunning = g.entries.some((e) => e.status === 'running');
      const hasFailed = g.entries.some((e) => e.status === 'failed');
      g.status = hasRunning ? 'running' : (hasFailed ? 'failed' : 'done');
  }

  order.sort((a, b) => {
    const ga = groups.get(a)!;
    const gb = groups.get(b)!;
    if (ga.status === 'running' && gb.status !== 'running') return -1;
    if (gb.status === 'running' && ga.status !== 'running') return 1;
    return ga.startedAt - gb.startedAt;
  });

  return order.map((pid) => groups.get(pid)!);
}

export interface PlanState {
  goal: string;
  goalDescription: string;
  goalPreconditions: Record<string, string>;
  goalKnownConditions: string[];
  totalSteps: number;
  steps: PlanStep[];
  agent?: PlanAgentInfo;
  validation?: PlanValidation;
}

export interface PlanValidation {
  valid: boolean;
  invalidSteps: string[];
  validSteps: string[];
  availableActions: string[];
  message: string;
}

export interface PlanAgentInfo {
  name: string;
  description: string;
  actions: { name: string; description: string }[];
  goals: string[];
}

export interface PlanStep {
  name: string;
  description: string;
  status: 'pending' | 'running' | 'done' | 'failed';
  index: number;
  preconditions: Record<string, string>;
  effects: Record<string, string>;
}

interface ChatStoreShape {
  chats: Record<string, ChatSessionState>;
  /** workspaceId → 上次活跃的 sessionId（切换 workspace 回来时恢复位置） */
  activeSessions: Record<string, string>;
}

const LS_ACTIVE = 'ec:activeSessions';
const LS_QUEUE_PREFIX = 'ec:queue:';

function loadLS<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    if (raw) return JSON.parse(raw) as T;
  } catch {
    // ignore
  }
  return fallback;
}

function saveLS(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // 配额已满或隐私模式：静默丢弃，队列最多丢失不影响核心对话
  }
}

function removeLS(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    // ignore
  }
}

function loadQueue(key: string): QueueItem[] {
  return loadLS<QueueItem[]>(LS_QUEUE_PREFIX + key, []);
}

function saveQueue(key: string, queue: QueueItem[]): void {
  saveLS(LS_QUEUE_PREFIX + key, queue);
}

function removeQueue(key: string): void {
  removeLS(LS_QUEUE_PREFIX + key);
}

const store: ChatStoreShape = {
  chats: {},
  activeSessions: loadLS<Record<string, string>>(LS_ACTIVE, {}),
};
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((l) => l());
}

export function chatKey(workspaceId: string, sessionId: string): string {
  return `${workspaceId}|${sessionId}`;
}

const INITIAL_STATE: ChatSessionState = {
  messages: [],
  running: false,
  pending: null,
  error: '',
  activeTools: [],
  activeSubagents: [],
  messageQueue: [],
};

export function getChatSession(key: string): ChatSessionState {
  let c = store.chats[key];
  if (!c) {
    c = { ...INITIAL_STATE };
    const persisted = loadQueue(key);
    if (persisted.length > 0) {
      c.messageQueue = persisted;
    }
    store.chats[key] = c;
  }
  return c;
}

export function updateChatSession(key: string, updater: (prev: ChatSessionState) => ChatSessionState): void {
  const prev = getChatSession(key);
  const next = updater(prev);
  store.chats[key] = next;
  if (next.messageQueue !== prev.messageQueue) {
    saveQueue(key, next.messageQueue);
  }
  emit();
}

export function useChatSession(key: string): ChatSessionState {
  const [, force] = useReducer((x: number) => x + 1, 0);
  useEffect(() => {
    const cb = () => force();
    listeners.add(cb);
    return () => {
      listeners.delete(cb);
    };
  }, []);
  return getChatSession(key);
}

export function clearChatSession(key: string): void {
  store.chats[key] = { ...INITIAL_STATE };
  removeQueue(key);
  emit();
}

/** 记住 workspace 上次活跃的会话（切换回来恢复位置，不重置到第一个） */
export function getActiveSession(workspaceId: string): string | undefined {
  return store.activeSessions[workspaceId];
}

export function setActiveSession(workspaceId: string, sessionId: string): void {
  if (store.activeSessions[workspaceId] !== sessionId) {
    store.activeSessions[workspaceId] = sessionId;
    saveLS(LS_ACTIVE, store.activeSessions);
    emit();
  }
}
