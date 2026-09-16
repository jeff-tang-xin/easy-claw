// hub 控制台 API 客户端：Bearer 鉴权 + 401 自动 refresh 重试一次 + 统一解析 ApiError。
// refresh 旋转令牌：并发 401 共享同一个 refresh Promise，避免旧 refresh 被旋转后二次使用导致全部失败。
import {clearSession, getAccessToken, getRefreshToken, saveSession} from './auth';
import type {
  AppKeyCreatedResponse,
  AppKeyDto,
  AuditLogPage,
  DocDto,
  DocEventDto,
  DocListItemDto,
  GatewayLogDetailDto,
  GatewayLogPage,
  GatewayUsageDto,
  MeResponse,
  MemberDto,
  OrgDto,
  OrgOptionDto,
  ProjectDto,
  ProviderDto,
  TokenResponse,
  UserDto,
} from './types';

/** 统一 API 错误：携带 HTTP 状态码与可选 details（如 409 时后端返回的最新文档快照）。 */
export class ApiRequestError extends Error {
  status: number;
  details: unknown;

  constructor(status: number, message: string, details?: unknown) {
    super(message);
    this.name = 'ApiRequestError';
    this.status = status;
    this.details = details;
  }
}

async function parseError(res: Response): Promise<Error> {
  try {
    const body = await res.json();
    if (body && typeof body.message === 'string') {
      return new ApiRequestError(res.status, body.message, (body as {details?: unknown}).details);
    }
  } catch {
    // 非 JSON 响应，落到状态码
  }
  return new ApiRequestError(res.status, `HTTP ${res.status}`);
}

let refreshing: Promise<boolean> | null = null;

function tryRefresh(): Promise<boolean> {
  if (!refreshing) {
    refreshing = doRefresh().finally(() => {
      refreshing = null;
    });
  }
  return refreshing;
}

async function doRefresh(): Promise<boolean> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return false;
  try {
    const res = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({refreshToken}),
    });
    if (!res.ok) return false;
    const t = (await res.json()) as TokenResponse;
    saveSession({accessToken: t.accessToken, refreshToken: t.refreshToken, user: t.user, orgs: t.orgs});
    return true;
  } catch {
    return false;
  }
}

async function request<T>(method: string, url: string, body?: unknown, retried = false): Promise<T> {
  const headers: Record<string, string> = {};
  const token = getAccessToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  const res = await fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  // access 过期 → 刷新后原样重试一次；refresh 也失败 → 清会话回登录页
  if (res.status === 401 && !retried && !url.startsWith('/api/auth/')) {
    if (await tryRefresh()) return request<T>(method, url, body, true);
    clearSession();
    window.location.href = '/login';
    throw new Error('登录已过期，请重新登录');
  }
  if (!res.ok) throw await parseError(res);
  if (res.status === 204) return null as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : null) as T;
}

// ============ 认证 ============
export const login = (usernameOrEmail: string, password: string) =>
  request<TokenResponse>('POST', '/api/auth/login', {usernameOrEmail, password});

/** 修改密码（含首登强制改密场景）；成功后旧 token 的 mcp 标记仍在，必须重新登录拿新 token */
export const changePassword = (oldPassword: string, newPassword: string) =>
  request<void>('POST', '/api/auth/change-password', {oldPassword, newPassword});

export const logout = () => {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return Promise.resolve();
  return request<void>('POST', '/api/auth/logout', {refreshToken});
};

// ============ 平台管理员：用户管理（公开注册已取消，用户仅由管理员开通） ============
export const adminListUsers = () => request<UserDto[]>('GET', '/api/users');

/** 临时密码由服务端生成并投递邮箱（A0 无 SMTP，服务端日志兜底），响应不含密码；
 *  orgId 给了则创建即入组（role 默认 member，owner 由服务端拒绝） */
export const adminCreateUser = (
  username: string,
  email: string,
  displayName: string | null,
  orgId: number | null,
  role: string | null,
) => request<UserDto>('POST', '/api/users', {username, email, displayName, orgId, role});

/** 全量组织下拉选项（仅 platformAdmin 可调通）：添加用户/分配 provider 归属用 */
export const listOrgOptions = () => request<OrgOptionDto[]>('GET', '/api/users/org-options');

/** 删除用户：不能删自己/平台管理员（服务端 403），调用方负责 confirm 与提示 */
export const adminDeleteUser = (id: number) => request<void>('DELETE', `/api/users/${id}`);

// ============ 当前用户 ============
export const fetchMe = () => request<MeResponse>('GET', '/api/me');

// ============ 组织与成员 ============
export const listMyOrgs = () => request<OrgDto[]>('GET', '/api/orgs');

/** slug 由服务端从名称派生，前端不传 */
export const createOrg = (name: string) =>
  request<OrgDto>('POST', '/api/orgs', {name});

export const listMembers = (orgId: number) =>
  request<MemberDto[]>('GET', `/api/orgs/${orgId}/members`);

export const addMember = (orgId: number, usernameOrEmail: string, role: string) =>
  request<void>('POST', `/api/orgs/${orgId}/members`, {usernameOrEmail, role});

export const updateMemberRole = (orgId: number, userId: number, role: string) =>
  request<void>('PATCH', `/api/orgs/${orgId}/members/${userId}`, {role});

export const removeMember = (orgId: number, userId: number) =>
  request<void>('DELETE', `/api/orgs/${orgId}/members/${userId}`);

// ============ 项目 ============
export const listProjects = (orgId: number) =>
  request<ProjectDto[]>('GET', `/api/projects?orgId=${orgId}`);

/** 单项目查询：服务端按可见性裁决（含组织外用户可读 public 项目），不可见返回 403/404 */
export const getProject = (id: number) => request<ProjectDto>('GET', `/api/projects/${id}`);

/** 创建项目请求体；slug 由服务端从名称派生，前端不传 */
export interface CreateProjectBody {
  orgId: number;
  name: string;
  description: string | null;
  visibility: string;
}

export const createProject = (body: CreateProjectBody) =>
  request<ProjectDto>('POST', '/api/projects', body);

export interface UpdateProjectBody {
  name?: string;
  description?: string;
  visibility?: string;
  status?: string;
}

export const updateProject = (id: number, body: UpdateProjectBody) =>
  request<ProjectDto>('PATCH', `/api/projects/${id}`, body);

export const archiveProject = (id: number) => request<void>('DELETE', `/api/projects/${id}`);

export const restoreProject = (id: number) => request<void>('POST', `/api/projects/${id}/restore`);

// ============ 项目文档（需求/任务，A2） ============
export const listDocs = (projectId: number, docType?: string) => {
  const params = new URLSearchParams({projectId: String(projectId)});
  if (docType) params.set('docType', docType);
  return request<DocListItemDto[]>('GET', `/api/docs?${params.toString()}`);
};

export const getDoc = (id: number) => request<DocDto>('GET', `/api/docs/${id}`);

export const getDocHistory = (id: number) =>
  request<DocEventDto[]>('GET', `/api/docs/${id}/history`);

export interface CreateDocBody {
  projectId: number;
  title: string;
  docType?: string | null;
  content?: string | null;
  parentDocId?: number | null;
}

export const createDoc = (body: CreateDocBody) => request<DocDto>('POST', '/api/docs', body);

/** 更新走乐观锁：expectedVersion 为编辑时基于的版本；冲突时 catch 到 ApiRequestError(status=409)，details 为最新 DocDto */
export interface UpdateDocBody {
  title?: string | null;
  content?: string | null;
  expectedVersion: number;
}

export const updateDoc = (id: number, body: UpdateDocBody) =>
  request<DocDto>('POST', `/api/docs/${id}`, body);

/** assigneeUserId=null 取消负责人 */
export const assignDoc = (id: number, assigneeUserId: number | null) =>
  request<DocDto>('POST', `/api/docs/${id}/assign`, {assigneeUserId});

/** 硬删文档（历史版本 doc_events 保留留痕） */
export const deleteDoc = (id: number) => request<void>('DELETE', `/api/docs/${id}`);

// ============ 审计日志 ============
/** 组织维度审计日志分页查询（仅 owner/admin 可调通，服务端强制校验） */
export const fetchAuditLogs = (orgId: number, module: string, page: number, size: number) => {
  const params = new URLSearchParams();
  if (module) params.set('module', module);
  params.set('page', String(page));
  params.set('size', String(size));
  return request<AuditLogPage>('GET', `/api/orgs/${orgId}/audit-logs?${params.toString()}`);
};

// ============ Provider（平台共享池 + 组织自有：列表=平台池+所在组织，管理权服务端强制校验） ============
export const listProviders = () => request<ProviderDto[]>('GET', '/api/providers');

/** apiKey 仅创建时提交一次；models 为模型名列表；orgId 省略=平台共享池（仅 platformAdmin） */
export interface CreateProviderBody {
  slug: string;
  name: string;
  baseUrl: string;
  apiKey: string;
  models: string[];
  remark: string | null;
  orgId: number | null;
  /** openai|anthropic，省略默认 openai */
  apiType?: string;
}

export const createProvider = (body: CreateProviderBody) =>
  request<ProviderDto>('POST', '/api/providers', body);

/** 部分更新；apiKey 不传表示不更换；组织归属创建后不可改 */
export interface UpdateProviderBody {
  name?: string;
  baseUrl?: string;
  apiKey?: string;
  models?: string[];
  status?: string;
  remark?: string | null;
  apiType?: string;
}

export const updateProvider = (id: number, body: UpdateProviderBody) =>
  request<ProviderDto>('PATCH', `/api/providers/${id}`, body);

/** 被 AppKey 绑定时服务端返回 409，错误 message 直接展示给用户 */
export const deleteProvider = (id: number) => request<void>('DELETE', `/api/providers/${id}`);

// ============ AppKey（组织级访问密钥，owner/admin 管理） ============
/** 创建/改绑用的绑定入参；modelName 省略或空串 = 该 provider 全部模型 */
export interface AppKeyBindingInput {
  providerId: number;
  modelName?: string;
}

export const listAppKeys = (orgId: number) =>
  request<AppKeyDto[]>('GET', `/api/orgs/${orgId}/appkeys`);

/** plainKey 仅在创建响应中出现一次，调用方必须醒目展示并提示保存 */
export const createAppKey = (orgId: number, name: string, bindings: AppKeyBindingInput[]) =>
  request<AppKeyCreatedResponse>('POST', `/api/orgs/${orgId}/appkeys`, {name, bindings});

export const revokeAppKey = (orgId: number, id: number) =>
  request<void>('POST', `/api/orgs/${orgId}/appkeys/${id}/revoke`);

export const updateAppKeyBindings = (orgId: number, id: number, bindings: AppKeyBindingInput[]) =>
  request<AppKeyDto>('PUT', `/api/orgs/${orgId}/appkeys/${id}/bindings`, {bindings});

// ============ LLM 网关（详单/用量，仅 owner/admin 可调通，服务端强制校验） ============
/** 详单分页查询：status/model/keyPrefix 过滤可组合，page 从 0 开始 */
export const fetchGatewayLogs = (
  orgId: number,
  filters: {status?: string; model?: string; keyPrefix?: string},
  page: number,
  size: number,
) => {
  const params = new URLSearchParams();
  if (filters.status) params.set('status', filters.status);
  if (filters.model) params.set('model', filters.model);
  if (filters.keyPrefix) params.set('keyPrefix', filters.keyPrefix);
  params.set('page', String(page));
  params.set('size', String(size));
  return request<GatewayLogPage>('GET', `/api/orgs/${orgId}/gateway-logs?${params.toString()}`);
};

/** 详单详情（含请求/响应正文） */
export const fetchGatewayLogDetail = (orgId: number, id: number) =>
  request<GatewayLogDetailDto>('GET', `/api/orgs/${orgId}/gateway-logs/${id}`);

/** 用量聚合：days 省略时由服务端给默认窗口 */
export const fetchGatewayUsage = (orgId: number, days?: number) => {
  const params = new URLSearchParams();
  if (days != null) params.set('days', String(days));
  const qs = params.toString();
  return request<GatewayUsageDto>('GET', `/api/orgs/${orgId}/gateway-logs/usage${qs ? `?${qs}` : ''}`);
};
