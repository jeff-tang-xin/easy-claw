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

export type SegmentType = 'text' | 'reasoning' | 'tool' | 'subagent' | 'note';

/** 子 Agent 内部的一步：正文 / 思考 / 工具调用，按到达顺序线性排列 */
export interface SubStep {
  kind: 'text' | 'reasoning' | 'tool';
  /** 正文或思考内容 */
  content?: string;
  /** 工具名（kind==='tool'） */
  name?: string;
  /** 工具入参（JSON 字符串） */
  args?: string;
  /** 工具结果 */
  result?: string;
  /** 工具结果状态，如 SUCCESS / ERROR */
  state?: string;
  /** 是否仍在执行 */
  running?: boolean;
}

export interface Segment {
  type: SegmentType;
  /** 正文 / 思考 / 子 Agent 正文 */
  content?: string;
  /** 工具名 或 子 Agent 名 */
  name?: string;
  /** 工具调用参数（JSON 字符串） */
  args?: string;
  /** 工具执行结果（含状态前缀，如 "(SUCCESS) ..."） */
  result?: string;
  /** 是否正在执行（用于渲染 spinner / 展开态） */
  running?: boolean;
  /** 开始时间戳（ms）。子 Agent / 工具节点用于计算耗时 */
  startedAt?: number;
  /** 结束时间戳（ms） */
  endedAt?: number;
  /** 子 Agent 内部步骤（type==='subagent'），按时间顺序 */
  steps?: SubStep[];
}

export interface ChatMessage {
  role: 'user' | 'ai';
  segments: Segment[];
  attachments?: { name: string; mimeType: string }[];
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
  /** 当前正在执行的工具名集合（tool 事件添加，tool_end 事件移除） */
  activeTools: string[];
  /** 当前正在运行的子 Agent 名集合（subagent 事件添加，subagent_end 事件移除） */
  activeSubagents: string[];
  /** 用户输入队列：running 时新消息入队，end 后自动发送 */
  messageQueue: QueueItem[];
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
