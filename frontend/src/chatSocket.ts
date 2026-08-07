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
  }
}

const listeners: ChatListener[] = [];
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let isCreating = false;
let nextConnId = window.__CHAT_SOCKET_CONN_ID__ || 1;

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

  console.log(`[ws#${connId}] 创建连接 url=${proto}://${location.host}/ws/chat`);

  ws.onopen = () => {
    isCreating = false;
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
      if (listeners.length > 0 && !reconnectTimer) {
        reconnectTimer = setTimeout(() => {
          reconnectTimer = null;
          reapDeadSockets();
          if (listeners.length > 0 && !isCreating && !window.__CHAT_SOCKET__) {
            getChatSocket();
          }
        }, 2000);
      }
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
