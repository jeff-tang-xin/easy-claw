// API 客户端：REST + SSE 流式解析

export interface StreamEvent {
  type: string;
  content: string;
  /** 工具类事件（tool / tool_args / tool_result / tool_end）携带的调用 id，用于精确配对 */
  toolCallId?: string;
  /**
   * 子 Agent 实例 id（subagent_* 事件）。来自框架的 agentInstanceId，
   * 用于区分并行派发的同名子 Agent —— content 里的名字是智能体名（agentId），两个实例完全相同。
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

export async function del<T = void>(url: string): Promise<T> {
  const res = await fetch(url, { method: 'DELETE' });
  if (!res.ok) throw new Error((await safeText(res)) || `HTTP ${res.status}`);
  // 204 / 空 body 时 safeJson 返回 null；调用方不读值则与旧版（void）行为一致
  return safeJson<T>(res);
}

async function safeText(res: Response): Promise<string> {
  try {
    return await res.text();
  } catch {
    return '';
  }
}

// ==================== 云端项目绑定（工作区 ↔ hub 项目，S6-S7） ====================

/** hub 项目（GET /api/manage/cloud/projects 元素） */
export interface CloudProject {
  id: number;
  name: string;
  slug: string;
}

/** 工作区与 hub 项目的绑定状态（GET /api/manage/workspaces/{id}/cloud-binding） */
export interface CloudBinding {
  bound: boolean;
  projectId: number | null;
  projectName: string | null;
}

/** hub 项目清单（OpsPage 绑定/换绑下拉数据源） */
export function getCloudProjects(): Promise<CloudProject[]> {
  return getJson<CloudProject[]>('/api/manage/cloud/projects');
}

/** 查询工作区当前的云端项目绑定 */
export function getCloudBinding(workspaceId: string): Promise<CloudBinding> {
  return getJson<CloudBinding>(`/api/manage/workspaces/${encodeURIComponent(workspaceId)}/cloud-binding`);
}

/** 绑定/换绑：把工作区绑定到指定 hub 项目 */
export function bindCloudWorkspace(workspaceId: string, projectId: number): Promise<void> {
  return postJson<void>(`/api/manage/workspaces/${encodeURIComponent(workspaceId)}/cloud-binding`, {projectId});
}

/** 解绑：解除工作区与 hub 项目的绑定 */
export function unbindCloudWorkspace(workspaceId: string): Promise<void> {
  return del<void>(`/api/manage/workspaces/${encodeURIComponent(workspaceId)}/cloud-binding`);
}

// ==================== 积分余额（V27，appkey 创建者维度，按 provider 一行） ====================

/**
 * 积分视图（GET /api/manage/cloud/credits 元素，hub 透传）。
 * remaining = 积分池可用余额（null = 该 provider 未启用积分池）；
 * dailyLimit/usedToday 为每日次数限流口径（null = 未限流）。
 */
export interface CloudCredit {
  providerId: number;
  providerName: string | null;
  providerSlug: string | null;
  remaining: number | null;
  dailyLimit: number | null;
  usedToday: number | null;
}

/** 当前用户（appkey 创建者）在各 provider 的积分/限流视图 */
export function getCloudCredits(): Promise<CloudCredit[]> {
  return getJson<CloudCredit[]>('/api/manage/cloud/credits');
}
