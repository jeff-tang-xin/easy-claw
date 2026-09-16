// 与 hub/contract DTO 一一对应的 TS 类型：后端 record 序列化为同名 JSON 字段，命名保持一致

export interface UserDto {
  id: number;
  username: string;
  email: string | null;
  displayName: string | null;
  status: string;
  /** 平台管理员（可管理用户） */
  platformAdmin: boolean;
  /** 首次登录须先改密（改密前其余 API 不可用，mcp 门禁） */
  mustChangePassword: boolean;
}

export interface OrgDto {
  id: number;
  name: string;
  slug: string;
  /** 当前用户在该组织的角色：owner|admin|member|guest */
  role: string;
  plan: string;
}

export interface MemberDto {
  userId: number;
  username: string;
  displayName: string | null;
  role: string;
  joinedAt: string | null;
}

export interface ProjectDto {
  id: number;
  orgId: number;
  slug: string;
  name: string;
  description: string | null;
  /** private|team|public */
  visibility: string;
  ownerUserId: number;
  /** 创建者展示名（displayName 优先，回落 username），可能为 null（用户已删除等） */
  ownerUsername: string | null;
  /** active|archived */
  status: string;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserDto;
  orgs: OrgDto[];
}

export interface MeResponse {
  user: UserDto;
  orgs: OrgDto[];
  currentOrgId: number | null;
  permissions: string[];
}

/** 统一错误响应体（GlobalExceptionHandler） */
export interface ApiError {
  code: string;
  message: string;
  details?: unknown;
}

/** 审计日志条目（只读视图） */
export interface AuditLogDto {
  id: number;
  module: string;
  action: string;
  actorUserId: number | null;
  actorUsername: string | null;
  orgId: number | null;
  targetType: string | null;
  targetId: string | null;
  result: string;
  detail: string | null;
  ip: string | null;
  userAgent: string | null;
  createdAt: string | null;
}

export interface AuditLogPage {
  items: AuditLogDto[];
  total: number;
  page: number;
  size: number;
}

// ============ Provider（模型供应商：平台共享池 + 组织自有；真实 key 服务端加密存储、永不回显） ============
export interface ProviderDto {
  id: number;
  slug: string;
  name: string;
  baseUrl: string;
  models: string[];
  /** active|disabled */
  status: string;
  remark: string | null;
  /** 脱敏后的密钥尾号提示（如 ****ab12），未配置为 null */
  keyHint: string | null;
  /** null = 平台共享池；否则归属组织 id */
  orgId: number | null;
  /** 归属组织名（平台池为 null），列表直接展示用 */
  orgName: string | null;
  /** 协议类型：openai = OpenAI 兼容；anthropic = Anthropic 原生（A1 网关按此分流） */
  apiType: string;
}

/** 组织下拉选项（添加用户/分配 provider 归属用，仅 platformAdmin 可调通） */
export interface OrgOptionDto {
  id: number;
  name: string;
  slug: string;
}

// ============ AppKey（组织级访问密钥，owner/admin 管理） ============
/** modelName 为空串表示该 provider 的全部模型 */
export interface AppKeyBindingDto {
  providerId: number;
  providerSlug: string | null;
  providerName: string | null;
  modelName: string;
}

export interface AppKeyDto {
  id: number;
  orgId: number;
  name: string;
  keyPrefix: string;
  /** active|revoked */
  status: string;
  createdBy: number;
  createdAt: string | null;
  lastUsedAt: string | null;
  revokedAt: string | null;
  bindings: AppKeyBindingDto[];
}

/** 创建 AppKey 响应：plainKey 仅此一次返回，必须立即保存 */
export interface AppKeyCreatedResponse {
  appKey: AppKeyDto;
  plainKey: string;
}

// ============ Docs（项目内协作文档：需求 requirement / 任务 task） ============
/** 文档列表项：不含正文 */
export interface DocListItemDto {
  id: number;
  projectId: number;
  parentDocId: number | null;
  title: string;
  /** requirement | task */
  docType: string;
  /** active | archived */
  status: string;
  ownerUserId: number;
  ownerUsername: string | null;
  assigneeUserId: number | null;
  assigneeUsername: string | null;
  version: number;
  updatedAt: string | null;
}

/** 文档详情：含当前版正文 */
export interface DocDto {
  id: number;
  projectId: number;
  parentDocId: number | null;
  title: string;
  docType: string;
  status: string;
  ownerUserId: number;
  ownerUsername: string | null;
  assigneeUserId: number | null;
  assigneeUsername: string | null;
  version: number;
  content: string;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 历史版本项（doc_events 一行快照，不可变） */
export interface DocEventDto {
  id: number;
  docId: number;
  version: number;
  title: string;
  content: string;
  actorUserId: number | null;
  actorUsername: string | null;
  createdAt: string | null;
}

// ============ LLM 网关（详单/用量，组织维度，仅 owner/admin 可见） ============
/** 网关详单列表项（不含请求/响应正文） */
export interface GatewayLogListItemDto {
  id: number;
  createdAt: string | null;
  appKeyId: number | null;
  keyPrefix: string | null;
  userId: number | null;
  providerId: number | null;
  providerSlug: string | null;
  /** openai|anthropic；未路由到 provider 的错误详单为 null */
  apiType: string | null;
  model: string | null;
  stream: boolean;
  /** success|error */
  status: string;
  httpStatus: number | null;
  latencyMs: number | null;
  promptTokens: number | null;
  completionTokens: number | null;
  errorMessage: string | null;
}

/** 网关详单详情：附完整请求/响应正文（SSE 为聚合内容，超长已截断标注） */
export interface GatewayLogDetailDto extends GatewayLogListItemDto {
  requestBody: string | null;
  responseBody: string | null;
  clientIp: string | null;
}

export interface GatewayLogPage {
  items: GatewayLogListItemDto[];
  total: number;
  page: number;
  size: number;
}

export interface GatewayModelUsage {
  model: string;
  requests: number;
  promptTokens: number;
  completionTokens: number;
}

/** 网关用量聚合（近 days 天窗口） */
export interface GatewayUsageDto {
  days: number;
  totalRequests: number;
  successRequests: number;
  promptTokens: number;
  completionTokens: number;
  byModel: GatewayModelUsage[];
}
