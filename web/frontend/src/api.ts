// API 客户端：REST + SSE 流式解析

export interface StreamEvent {
  type: string;
  content: string;
  /** 工具类事件（tool / tool_args / tool_result / tool_end）携带的调用 id，用于精确配对 */
  toolCallId?: string;
  /**
   * 子 Agent 实例 id（subagent_* 事件）。来自框架的 agentInstanceId，
   * 用于区分并行派发的同名子 Agent —— content 里的名字是角色名，两个实例完全相同。
   * 历史转录回放时为 undefined，此时退化为按名字归并。
   */
  subId?: string;
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
