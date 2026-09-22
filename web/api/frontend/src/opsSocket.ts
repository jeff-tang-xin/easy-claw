// 运维终端专用 WebSocket 连接
// 设计：与 chatSocket.ts 的全局单例分开 —— 后端 term_* 出站消息是裸 JSON
// （{type,terminalId,data}，不带 sessionId/event 包装），全局聊天单例的 onmessage
// 会把这类消息静默丢弃；终端页自建一条 /ws/chat 连接，生命周期随页面挂载/卸载。
// 同一条连接同时承载智能幕布的对话事件（register/chat/confirm 走 sessionId 信封，
// 入站 {workspaceId,sessionId,event:{type,content,toolCallId}}，LegacyEventSerializer 协议）。
// 后端对每条 WS 连接独立管理终端归属（terminalOwners），多连接互不干扰。

export interface OpsSocket {
  send: (obj: unknown) => void;
  close: () => void;
}

/** 对话事件体（StreamEvent 的 JSON 形状，字段缺省 = 后端 NON_NULL 序列化） */
export interface OpsChatEvent {
  type: string;
  content?: string;
  toolCallId?: string;
}

export interface OpsSocketHandlers {
  /** 连接建立（页面此时才能发 register / term_open） */
  onOpen: () => void;
  /** 终端输出（base64 编码的原始字节，含 ANSI 序列） */
  onData: (terminalId: string, base64: string) => void;
  /** 终端错误（连接失败/终端不存在等，可直接展示） */
  onError: (terminalId: string, message: string) => void;
  /** 对话事件（带其所属 sessionId —— 多 tab 一个连接一个会话，按此路由回所属 tab） */
  onChatEvent: (sessionId: string, ev: OpsChatEvent) => void;
  /** 连接断开（后端会随之清理该连接的全部终端，页面应重置终端状态） */
  onClose: () => void;
}

export function createOpsSocket(handlers: OpsSocketHandlers): OpsSocket {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws/chat`);

  ws.onopen = () => handlers.onOpen();
  ws.onclose = () => handlers.onClose();
  ws.onerror = () => handlers.onClose();

  ws.onmessage = (e) => {
    try {
      const obj = JSON.parse(String(e.data)) as {
        type?: string;
        terminalId?: string;
        data?: string;
        message?: string;
        sessionId?: string;
        event?: OpsChatEvent;
      };
      if (obj.type === 'term_data' && obj.terminalId && typeof obj.data === 'string') {
        handlers.onData(obj.terminalId, obj.data);
      } else if (obj.type === 'term_error' && obj.terminalId) {
        handlers.onError(obj.terminalId, obj.message || '终端错误');
      } else if (obj.sessionId && obj.event && obj.event.type) {
        // 不再按「本页唯一会话」过滤：多 tab 各有会话，全部透传，由页面按 sessionId 路由
        handlers.onChatEvent(obj.sessionId, obj.event);
      }
      // 其余（pong 等）忽略
    } catch {
      // 非 JSON 消息，忽略
    }
  };

  return {
    send: (obj: unknown) => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(obj));
      }
    },
    close: () => {
      try {
        ws.close();
      } catch {
        // 已关闭，忽略
      }
    },
  };
}
