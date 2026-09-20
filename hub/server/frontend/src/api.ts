// hub 控制台 API 客户端：Bearer 鉴权 + 401 自动 refresh 重试一次 + 统一解析 ApiError。
// refresh 旋转令牌：并发 401 共享同一个 refresh Promise，避免旧 refresh 被旋转后二次使用导致全部失败。
import {clearSession, getAccessToken, getRefreshToken, saveSession} from './auth';
import type {
  AppKeyCreatedResponse,
  AppKeyDto,
  AuditLogPage,
  BlackboardEntryDto,
  CreatedUserDto,
  GatewayLogDetailDto,
  GatewayLogPage,
  GatewayUsageDto,
  KnowledgeHistoryItemDto,
  KnowledgeHistoryVersionDto,
  KnowledgeItemDto,
  KnowledgeItemListItemDto,
  MeResponse,
  MemberDto,
  MenuItemDto,
  OrgDto,
  OrgOptionDto,
  ProjectDto,
  ProviderDto,
  RoleMatrixResponse,
  TokenResponse,
  UserDto,
  WorkspaceDto,
} from './types';

/** 统一 API 错误：携带 HTTP 状态码、业务错误码 code（后端 ApiError.code）与可选 details（如 409 时最新文档快照）。 */
export class ApiRequestError extends Error {
  status: number;
  details: unknown;
  /** 业务错误码（如 PASSWORD_EXPIRED），调用方据此分支处理；非 JSON 响应时为 undefined */
  code?: string;

  constructor(status: number, message: string, details?: unknown, code?: string) {
    super(message);
    this.name = 'ApiRequestError';
    this.status = status;
    this.details = details;
    this.code = code;
  }
}

async function parseError(res: Response): Promise<Error> {
  try {
    const body = await res.json();
    if (body && typeof body.message === 'string') {
      return new ApiRequestError(
        res.status,
        body.message,
        (body as {details?: unknown}).details,
        (body as {code?: string}).code,
      );
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

/** 创建用户：响应含一次性明文 tempPassword（仅本次返回，服务端不落库、不投递邮箱，关掉即不可再查）；
 *  orgId 给了则创建即入组（role 默认 member，owner 由服务端拒绝） */
export const adminCreateUser = (
  username: string,
  email: string,
  displayName: string | null,
  orgId: number | null,
  role: string | null,
) => request<CreatedUserDto>('POST', '/api/users', {username, email, displayName, orgId, role});

/** 重置密码：返回一次性新密码；该用户当前各端登录立即失效，且下次登录强制改密 */
export const adminResetUserPassword = (id: number) =>
  request<CreatedUserDto>('POST', `/api/users/${id}/reset-password`);

/** 全量组织下拉选项（仅 platformAdmin 可调通）：添加用户/分配 provider 归属用 */
export const listOrgOptions = () => request<OrgOptionDto[]>('GET', '/api/users/org-options');

/** 删除用户：不能删自己/平台管理员（服务端 403），调用方负责 confirm 与提示 */
export const adminDeleteUser = (id: number) => request<void>('DELETE', `/api/users/${id}`);

// ============ 当前用户 ============
export const fetchMe = () => request<MeResponse>('GET', '/api/me');

/** 只读角色→权限码矩阵（含平台管理员叠加权限）；登录即可读，数据为全局静态规则 */
export const fetchRoleMatrix = () => request<RoleMatrixResponse>('GET', '/api/role-matrix');

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

/** 配置 hub_cloud 别名默认路由：modelName 必填且为具体模型（不支持“全部模型”） */
export const updateAppKeyCloudRoute = (
  orgId: number,
  id: number,
  providerId: number,
  modelName: string,
) =>
  request<AppKeyDto>('PUT', `/api/orgs/${orgId}/appkeys/${id}/cloud-route`, {providerId, modelName});

/** 清除 hub_cloud 别名默认路由（停用别名），幂等 */
export const clearAppKeyCloudRoute = (orgId: number, id: number) =>
  request<AppKeyDto>('DELETE', `/api/orgs/${orgId}/appkeys/${id}/cloud-route`);

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

// ============ Spoke 工作区（project 面向 spoke 的扩展面，1:1 绑定） ============
/** 组织下工作区列表：按绑定 project 的可见性过滤 */
export const listWorkspaces = (orgId: number) =>
  request<WorkspaceDto[]>('GET', `/api/workspaces?orgId=${orgId}`);

/** 单工作区查询（读鉴权按绑定 project 可见性） */
export const getWorkspace = (id: number) => request<WorkspaceDto>('GET', `/api/workspaces/${id}`);

/** 创建工作区：projectId 指定要绑定的项目（1:1，重复绑定→409）；name 留空由服务端取项目名 */
export const createWorkspace = (projectId: number, name: string) =>
  request<WorkspaceDto>('POST', '/api/workspaces', {projectId, name: name.trim() || null});

/** 改工作区：name 改展示名，status 归档/恢复（active|archived） */
export const updateWorkspace = (id: number, body: {name?: string; status?: string}) =>
  request<WorkspaceDto>('PATCH', `/api/workspaces/${id}`, body);

/** 归档工作区（软删）并级联删除其菜单配置 */
export const archiveWorkspace = (id: number) => request<void>('DELETE', `/api/workspaces/${id}`);

// ============ Spoke 公共菜单（绑定工作区，与组织无关） ============
export const listMenus = (workspaceId: number) =>
  request<MenuItemDto[]>('GET', `/api/workspaces/${workspaceId}/menus`);

export interface CreateMenuBody {
  menuKey?: string;
  label: string;
  icon?: string;
  path?: string;
  parentId?: number | null;
  requiredPerm?: string;
  visibleRoles?: string;
  sortOrder?: number;
  enabled?: boolean;
}

export const createMenu = (workspaceId: number, body: CreateMenuBody) =>
  request<MenuItemDto>('POST', `/api/workspaces/${workspaceId}/menus`, body);

export interface UpdateMenuBody {
  menuKey?: string;
  label?: string;
  icon?: string;
  path?: string;
  requiredPerm?: string;
  visibleRoles?: string;
  sortOrder?: number;
  enabled?: boolean;
}

export const updateMenu = (menuId: number, body: UpdateMenuBody) =>
  request<MenuItemDto>('PATCH', `/api/menus/${menuId}`, body);

/** 启用/停用菜单项（下发时仅含 enabled 项） */
export const toggleMenu = (menuId: number, enabled: boolean) =>
  request<MenuItemDto>('POST', `/api/menus/${menuId}/toggle?enabled=${enabled}`);

/** 删除菜单项，级联删除其全部子孙 */
export const deleteMenu = (menuId: number) => request<void>('DELETE', `/api/menus/${menuId}`);

// ============ 项目知识库（A3-S1：CRUD + 乐观锁 + 软删 + 版本历史） ============
export const listKnowledgeItems = (projectId: number) =>
  request<KnowledgeItemListItemDto[]>('GET', `/api/knowledge?projectId=${projectId}`);

export const getKnowledgeItem = (id: number) =>
  request<KnowledgeItemDto>('GET', `/api/knowledge/${id}`);

export const getKnowledgeHistory = (id: number) =>
  request<KnowledgeHistoryItemDto[]>('GET', `/api/knowledge/${id}/history`);

export const getKnowledgeHistoryVersion = (id: number, version: number) =>
  request<KnowledgeHistoryVersionDto>('GET', `/api/knowledge/${id}/history/${version}`);

export interface CreateKnowledgeItemBody {
  projectId: number;
  topic: string;
  summary?: string | null;
  content?: string | null;
}

export const createKnowledgeItem = (body: CreateKnowledgeItemBody) =>
  request<KnowledgeItemDto>('POST', '/api/knowledge', body);

/** 更新走乐观锁：expectedVersion 为编辑时基于的版本；冲突时 catch 到 ApiRequestError(status=409)，details 为最新 KnowledgeItemDto */
export interface UpdateKnowledgeItemBody {
  topic?: string | null;
  summary?: string | null;
  content?: string | null;
  expectedVersion: number;
}

export const updateKnowledgeItem = (id: number, body: UpdateKnowledgeItemBody) =>
  request<KnowledgeItemDto>('POST', `/api/knowledge/${id}`, body);

/** 软删知识条目（历史版本 knowledge_item_events 保留留痕） */
export const deleteKnowledgeItem = (id: number) =>
  request<void>('DELETE', `/api/knowledge/${id}`);

// ============ 项目黑板报（A4：追加型 + 归档状态标签） ============
export const listBlackboardActive = (projectId: number) =>
  request<BlackboardEntryDto[]>('GET', `/api/blackboard?projectId=${projectId}`);

export const listBlackboardArchives = (projectId: number) =>
  request<BlackboardEntryDto[]>('GET', `/api/blackboard/archives?projectId=${projectId}`);

export const appendBlackboardEntry = (projectId: number, content: string) =>
  request<BlackboardEntryDto>('POST', '/api/blackboard', {projectId, content});

/** 归档条目（幂等：已归档直接返回当前态） */
export const archiveBlackboardEntry = (id: number) =>
  request<BlackboardEntryDto>('POST', `/api/blackboard/${id}/archive`);
