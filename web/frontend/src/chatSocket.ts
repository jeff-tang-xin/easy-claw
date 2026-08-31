// 全局 WebSocket 单例
// 设计：模块加载时立即创建连接，全应用共享这一个 WS。
// 用 window.__CHAT_SOCKET__ 持久化引用，防止 Vite HMR 替换模块后 sock 变量被重置。
// 所有业务消息携带 workspaceId + sessionId 做路由，后端按这两个字段分发事件。
import type {StreamEvent} from './api';

export interface ChatSocket {
  send: (obj: unknown) => void;
  getConnId: () => number;
}

type ChatListener = (workspaceId: string, sessionId: string, evt: StreamEvent) => void;

// ---- window 全局引用（跨模块热更新存活） ----
declare global {
  interface Window {
    __CHAT_SOCKET__?: ChatSocket;
    __CHAT_SOCKET_CONN_ID__?: number;
    __CHAT_SOCKET_DEAD__?: WebSocket[];
    __CHAT_SOCKET_WAKE_BOUND__?: boolean;
  }
}

const listeners: ChatListener[] = [];
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let isCreating = false;
let nextConnId = window.__CHAT_SOCKET_CONN_ID__ || 1;

// 重连退避：连续失败时逐步拉长间隔，避免服务端未起来时每 2s 无脑重试。
// 首次只等 1s（比原来的固定 2s 更快恢复瞬时抖动），随后 2/4/8/15s 封顶。
const RECONNECT_DELAYS_MS = [1000, 2000, 4000, 8000, 15000];
// 连接存活超过这个时长才认为「真正连上过」，据此清零退避计数。
// 若服务端接受连接后立刻断开（崩溃重启循环），不清零，否则会退化成 1s 死循环。
const STABLE_CONNECTION_MS = 5000;
let reconnectAttempts = 0;

const queue: unknown[] = [];
const QUEUE_MAX = 200;

function reapDeadSockets() {
  const dead = window.__CHAT_SOCKET_DEAD__ || [];
  for (let i = dead.length - 1; i >= 0; i--) {
    const ws = dead[i];
    if (ws.readyState === WebSocket.CLOSED) {
      dead.splice(i, 1);
    } else {
      try { ws.close(); } catch {}
    }
  }
  window.__CHAT_SOCKET_DEAD__ = dead;
}

function flushQueue(ws: WebSocket) {
  while (queue.length > 0 && ws.readyState === WebSocket.OPEN) {
    const msg = queue.shift();
    try {
      ws.send(JSON.stringify(msg));
    } catch {
      queue.unshift(msg);
      break;
    }
  }
}

/**
 * 按退避档位安排一次重连。已有定时器时直接返回，保证同一时刻只有一个待重连任务。
 */
function scheduleReconnect() {
  if (listeners.length === 0 || reconnectTimer) return;
  const delay = RECONNECT_DELAYS_MS[Math.min(reconnectAttempts, RECONNECT_DELAYS_MS.length - 1)];
  reconnectAttempts++;
  console.log(`[ws] 计划第 ${reconnectAttempts} 次重连，延迟 ${delay}ms`);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    reapDeadSockets();
    if (listeners.length > 0 && !isCreating && !window.__CHAT_SOCKET__) {
      getChatSocket();
    }
  }, delay);
}

/**
 * 立即重连（不等退避）：用于「用户回到页面」「网络恢复」这类明确的复活信号。
 * 这是体验上最关键的一环 —— 笔记本合盖再打开后，用户不该盯着「正在重连」等十几秒。
 */
function reconnectNow() {
  if (window.__CHAT_SOCKET__ || isCreating || listeners.length === 0) return;
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  reconnectAttempts = 0;
  console.log('[ws] 收到复活信号（页面可见/网络恢复），立即重连');
  reapDeadSockets();
  getChatSocket();
}

// 浏览器给出的两个明确复活信号。addEventListener 而非赋值，避免覆盖他处监听。
if (typeof window !== 'undefined' && !window.__CHAT_SOCKET_WAKE_BOUND__) {
  window.__CHAT_SOCKET_WAKE_BOUND__ = true;
  window.addEventListener('online', reconnectNow);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') reconnectNow();
  });
}

function createSocket(): ChatSocket {
  isCreating = true;
  reapDeadSockets();

  const connId = nextConnId++;
  window.__CHAT_SOCKET_CONN_ID__ = nextConnId;

  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws/chat`);
  let heartbeat: ReturnType<typeof setInterval> | null = null;
  let notifiedClose = false;
  let socketRef: ChatSocket | null = null;
  let openedAt = 0;

  console.log(`[ws#${connId}] 创建连接 url=${proto}://${location.host}/ws/chat`);

  ws.onopen = () => {
    isCreating = false;
    openedAt = Date.now();
    flushQueue(ws);
    heartbeat = setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'ping' }));
      }
    }, 25000);
    console.log(`[ws#${connId}] 已连接`);
    listeners.forEach((l) => l('', '', { type: 'reconnected', content: '' } as StreamEvent));
  };

  ws.onmessage = (e) => {
    try {
      const obj = JSON.parse(String(e.data)) as { workspaceId?: string; sessionId: string; event: StreamEvent };
      if (obj && obj.sessionId && obj.event) {
        listeners.forEach((l) => l(obj.workspaceId || '', obj.sessionId, obj.event));
      }
    } catch (err) {
      console.warn(`[ws#${connId}] 消息解析失败:`, err);
    }
  };

  ws.onerror = () => {
    console.warn(`[ws#${connId}] onerror`);
  };

  ws.onclose = () => {
    isCreating = false;
    if (heartbeat) {
      clearInterval(heartbeat);
      heartbeat = null;
    }
    if (!notifiedClose) {
      notifiedClose = true;
      if (socketRef === window.__CHAT_SOCKET__) {
        window.__CHAT_SOCKET__ = undefined;
      }
      const dead = window.__CHAT_SOCKET_DEAD__ || [];
      dead.push(ws);
      window.__CHAT_SOCKET_DEAD__ = dead;
      console.warn(`[ws#${connId}] 断开（dead=${dead.length}）`);
      listeners.forEach((l) => l('', '', { type: 'disconnected', content: '' } as StreamEvent));
      // 稳定连接过后才断开 → 视为新一轮故障，退避从头开始；
      // 连上就秒断则保留当前退避档位，避免服务端重启循环时 1s 无脑重试
      if (openedAt > 0 && Date.now() - openedAt >= STABLE_CONNECTION_MS) {
        reconnectAttempts = 0;
      }
      scheduleReconnect();
    }
  };

  socketRef = {
    send: (obj) => {
      const t = (obj as any)?.type || '?';
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(obj));
      } else {
        queue.push(obj);
        if (queue.length > QUEUE_MAX) queue.shift();
        console.warn(`[ws#${connId}] QUEUED (state=${ws.readyState}):`, t, 'qLen=', queue.length);
        if (!isCreating && !window.__CHAT_SOCKET__) {
          getChatSocket();
        }
      }
    },
    getConnId: () => connId,
  };
  window.__CHAT_SOCKET__ = socketRef;
  return socketRef;
}

// 全局唯一获取入口：永远返回同一个 socket（除非断连重连）
export function getChatSocket(): ChatSocket {
  reapDeadSockets();

  // 已有存活连接 → 直接返回
  const existing = window.__CHAT_SOCKET__;
  if (existing) return existing;

  // 创建中 → 返回临时队列对象（不产生新连接）
  if (isCreating) {
    return {
      send: (obj) => {
        queue.push(obj);
        if (queue.length > QUEUE_MAX) queue.shift();
      },
      getConnId: () => 0,
    };
  }

  // 原子标记 + 创建
  isCreating = true;
  console.log('[ws] getChatSocket() → 创建新连接');
  const sock = createSocket();
  return sock;
}

export function subscribeChatSocket(cb: ChatListener): () => void {
  const sock = getChatSocket();
  listeners.push(cb);
  // 如果当前 socket 还没连上（CONNECTING），后续 onopen 会通知 reconnected
  // 如果已经连上，立即触发一次 reconnected 让新订阅者同步状态
  // 但为避免重复消息，这里只通知连接级事件
  void sock;
  return () => {
    const idx = listeners.indexOf(cb);
    if (idx >= 0) listeners.splice(idx, 1);
  };
}

// ---- 模块加载时立即初始化（真正的全局单例入口）----
// 这样无论 React 组件如何 mount/unmount，socket 只创建一次
const _initSock = getChatSocket();
console.log('[ws] 模块加载完成，初始 socket 已就绪:', _initSock.getConnId() > 0 ? `#${_initSock.getConnId()}` : 'CONNECTING');
