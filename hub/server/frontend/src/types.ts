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
  /** 当前密码到期时刻（改密后重新起算）；ISO-8601 字符串 */
  passwordExpiresAt: string | null;
  /** 已进入到期前提醒窗口 */
  passwordExpiringSoon: boolean;
}

/**
 * 管理员创建用户 / 重置密码的响应：UserDto + 一次性明文密码。
 * tempPassword 仅本次返回，服务端不落库、不投递邮箱，关闭弹窗后无法再次查看。
 */
export interface CreatedUserDto extends UserDto {
  tempPassword: string;
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
  /** 逻辑模型别名 hub_cloud 的默认路由落点；未配置时为 null */
  cloudRoute: AppKeyBindingDto | null;
}

/** 创建 AppKey 响应：plainKey 仅此一次返回，必须立即保存 */
export interface AppKeyCreatedResponse {
  appKey: AppKeyDto;
  plainKey: string;
}

// ============ 知识库（项目内共享知识条目，乐观锁 + 软删 + 版本历史） ============
/** 知识条目列表项：不含正文 */
export interface KnowledgeItemListItemDto {
  id: number;
  projectId: number;
  topic: string;
  summary: string;
  version: number;
  status: string;
  ownerUserId: number;
  ownerUsername: string | null;
  updatedBy: number | null;
  updatedByUsername: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 知识条目详情：含当前版正文 */
export interface KnowledgeItemDto {
  id: number;
  projectId: number;
  topic: string;
  summary: string;
  content: string;
  version: number;
  status: string;
  ownerUserId: number;
  ownerUsername: string | null;
  updatedBy: number | null;
  updatedByUsername: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 历史版本项（knowledge_item_events 一行快照，不可变） */
export interface KnowledgeHistoryItemDto {
  version: number;
  topic: string;
  summary: string;
  actorUserId: number | null;
  actorUsername: string | null;
  createdAt: string | null;
}

/** 历史版本详情：含该版正文 */
export interface KnowledgeHistoryVersionDto extends KnowledgeHistoryItemDto {
  content: string;
}

// ============ 黑板报（项目共享，追加型 + 归档状态标签） ============
export interface BlackboardEntryDto {
  id: number;
  projectId: number;
  content: string;
  authorUserId: number;
  authorUsername: string | null;
  /** active | archived */
  status: string;
  createdAt: string | null;
  updatedAt: string | null;
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

// ============ Spoke 工作区（project 面向 spoke 的扩展面，1:1 绑定） ============
/** 工作区视图：menuCount 为该工作区已配置的菜单项总数（含子项），不含菜单明细 */
export interface WorkspaceDto {
  id: number;
  projectId: number;
  orgId: number;
  name: string;
  /** active | archived */
  status: string;
  menuCount: number;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 菜单项视图（管理面，扁平结构）：绑定工作区、与组织无关 */
export interface MenuItemDto {
  id: number;
  workspaceId: number;
  parentId: number | null;
  /** spoke 侧稳定标识（同工作区内唯一） */
  menuKey: string;
  label: string;
  icon: string;
  path: string;
  /** 可见性权限码；空=不要求 */
  requiredPerm: string;
  /** 逗号分隔角色；空=全部角色可见 */
  visibleRoles: string;
  sortOrder: number;
  enabled: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 单个组织角色及其权限码（后端 roles 顺序固定：owner/admin/member/guest） */
export interface RolePerms {
  role: string;
  permissions: string[];
}

/**
 * GET /api/role-matrix 响应：组织角色 → 权限码的只读快照。
 * 数据由服务端 Permissions 单一定义，platformAdminPerms 为平台管理员叠加的权限码。
 */
export interface RoleMatrixResponse {
  roles: RolePerms[];
  platformAdminPerms: string[];
}
