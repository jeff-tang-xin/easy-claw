// API 客户端：REST + SSE 流式解析

export interface StreamEvent {
  type: string;
  content: string;
  /** 工具类事件（tool / tool_args / tool_result / tool_end）携带的调用 id，用于精确配对 */
  toolCallId?: string;
}

async function safeJson<T>(res: Response): Promise<T> {
  const text = await res.text().catch(() => '');
  if (!text) return null as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    // 后端返回了非 JSON 文本（如纯字符串），直接返回
    return text as unknown as T;
  }
}

export async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) throw new Error((await safeText(res)) || `HTTP ${res.status}`);
  return safeJson<T>(res);
}

export async function postJson<T>(url: string, body: unknown): Promise<T> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error((await safeText(res)) || `HTTP ${res.status}`);
  return safeJson<T>(res);
}

export async function putJson<T>(url: string, body: unknown): Promise<T> {
  const res = await fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error((await safeText(res)) || `HTTP ${res.status}`);
  return safeJson<T>(res);
}

export async function del(url: string): Promise<void> {
  const res = await fetch(url, { method: 'DELETE' });
  if (!res.ok) throw new Error((await safeText(res)) || `HTTP ${res.status}`);
}

async function safeText(res: Response): Promise<string> {
  try {
    return await res.text();
  } catch {
    return '';
  }
}

/**
 * POST SSE 流式对话：解析 text/event-stream，逐事件回调
 */
export async function streamChat(
  body: unknown,
  onEvent: (evt: StreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await Promise.race([
    fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal,
    }),
    new Promise<Response>((_, reject) =>
      setTimeout(() => reject(new Error('SSE 连接超时（20 秒无响应），请查看后端日志')), 20000),
    ),
  ]);
  if (!res.ok || !res.body) {
    throw new Error(`流式连接失败: ${res.status} ${res.statusText}`);
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const raw = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      const dataLine = raw.split('\n').find((l) => l.startsWith('data:'));
      if (dataLine) {
        try {
          onEvent(JSON.parse(dataLine.slice(5).trim()) as StreamEvent);
        } catch {
          // 忽略非 JSON 行
        }
      }
    }
  }
}
