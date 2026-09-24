// 与 hub/contract DTO 一一对应的 TS 类型：后端 record 序列化为同名 JSON 字段，命名保持一致

export interface UserDto {
  id: number;
  username: string;
  email: string | null;
  displayName: string | null;
  status: string;
  /** 平台管理员（可管理用户与平台目录） */
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

// ============ Provider 授权（按用户授权 + 可选每日次数上限/有效期；无授权行 = 开放） ============
export interface ProviderGrantDto {
  id: number;
  providerId: number;
  userId: number;
  /** 用户名（列表展示用） */
  username: string | null;
  /** null = 不限流 */
  dailyLimit: number | null;
  /** null = 永久 */
  expiresAt: string | null;
  /** 今日已用次数（仅配置了 dailyLimit 时返回） */
  usedToday: number | null;
  /** 周期积分发放计划：每日（当天有效次日重发）；null = 不发放 */
  dailyCredits: number | null;
  /** 周期积分发放计划：每月；null = 不发放 */
  monthlyCredits: number | null;
  /** 周期积分发放计划：每年；null = 不发放 */
  yearlyCredits: number | null;
  /** 积分池可用余额（Σ 未过期面额 − 已消耗）；null = 未启用积分池 */
  remainingCredits: number | null;
}

/** 积分流水行（管理端）：periodKey 为当期标识（daily=2026-09-24 / monthly=2026-09 / yearly=2026），temp 为 null */
export interface ProviderCreditDto {
  id: number;
  periodType: string;
  periodKey: string | null;
  credits: number;
  consumed: number;
  /** 面额 − 已消耗；已过期行的 remaining 不再计入可用余额 */
  remaining: number;
  expiresAt: string;
  createdAt: string;
}

// ============ 模型目录（平台级模型清单与积分比例；provider.models 按名称引用） ============
export interface ModelCatalogDto {
  id: number;
  modelName: string;
  /** 每次请求消耗积分（1 位小数；未登记模型默认 1） */
  creditCost: number;
  remark: string | null;
  createdAt: string;
}

// ============ 积分使用情况（我的余额/构成/使用记录 + 管理员组织总览） ============
/** 我的积分余额（按 provider 一行）：remaining = 积分池总剩余；四项构成为各周期未过期剩余 */
export interface CreditBalanceDto {
  providerId: number;
  providerName: string | null;
  providerSlug: string | null;
  /** null = 未启用积分池 */
  remaining: number | null;
  dailyRemaining: number | null;
  monthlyRemaining: number | null;
  yearlyRemaining: number | null;
  tempRemaining: number | null;
  dailyLimit: number | null;
  usedToday: number | null;
}

/** 使用记录（每次请求一条）：modelName 为路由后实际生效模型，cost 为本次消耗积分 */
export interface CreditUsageDto {
  id: number;
  providerId: number;
  providerName: string | null;
  providerSlug: string | null;
  modelName: string;
  cost: number;
  createdAt: string;
}

/** 积分总览行（管理员视角，每条授权一行）：provider × 用户维度 */
export interface CreditGrantSummaryDto {
  grantId: number;
  providerId: number;
  providerName: string | null;
  providerSlug: string | null;
  userId: number;
  username: string | null;
  remaining: number | null;
  dailyRemaining: number | null;
  monthlyRemaining: number | null;
  yearlyRemaining: number | null;
  tempRemaining: number | null;
  dailyLimit: number | null;
  usedToday: number | null;
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

// ============ 知识库（项目内共享知识条目，乐观锁 + 软删 + 版本历史；V23 起含 spoke 同步条目） ============
/** 知识条目列表项：不含正文（后端契约无 createdAt，仅 updatedAt） */
export interface KnowledgeItemListItemDto {
  id: number;
  projectId: number;
  topic: string;
  summary: string;
  version: number;
  status: string;
  /** workspace 来源条目为 0（Agent 写入占位，无 hub 用户） */
  ownerUserId: number;
  ownerUsername: string | null;
  updatedBy: number | null;
  updatedByUsername: string | null;
  updatedAt: string | null;
  /** 向量化状态：pending|done|failed（向量本身不进契约，仅状态标量） */
  embeddingStatus: string;
  /** 来源：platform=hub 平台创建 | workspace=spoke 工作区同步写入（Agent） */
  source: string;
  /** spoke 同步来源工作区标识（source=workspace 时非空） */
  sourceWorkspaceId: string | null;
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
  /** workspace 来源条目为 0（Agent 写入占位，无 hub 用户） */
  ownerUserId: number;
  ownerUsername: string | null;
  updatedBy: number | null;
  updatedByUsername: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  /** 向量化状态：pending|done|failed（向量本身不进契约，仅状态标量） */
  embeddingStatus: string;
  /** 来源：platform=hub 平台创建 | workspace=spoke 工作区同步写入（Agent） */
  source: string;
  /** spoke 同步来源工作区标识（source=workspace 时非空） */
  sourceWorkspaceId: string | null;
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

// ============ 黑板报（项目共享，追加型 + 归档状态标签；V24 起含 spoke Agent 同步条目） ============
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
  /** platform = 平台用户写入 | workspace = spoke AI Agent 同步写入 */
  source: string;
  /** 来源工作区标识（source=workspace 时非空） */
  sourceWorkspaceId: string | null;
  /** 条目类型（Agent 写入：note|finding|risk|conclusion…；平台写入为 null） */
  entryType: string | null;
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

// ============ 平台目录（菜单/功能开关/工具，platformAdmin 维护）与组织开关 ============
/** 平台菜单目录项（管理面，扁平结构）：平台级内置目录，对所有组织通用 */
export interface MenuItemDto {
  id: number;
  parentId: number | null;
  /** spoke 侧稳定标识（全局唯一） */
  menuKey: string;
  label: string;
  icon: string;
  path: string;
  /** 可见性权限码；空=不要求 */
  requiredPerm: string;
  /** 逗号分隔角色；空=全部角色可见 */
  visibleRoles: string;
  sortOrder: number;
  /** 平台总开关 */
  enabled: boolean;
}

/** 平台功能开关目录项：全部平台内置，组织只能决定是否启用 */
export interface FeatureFlagDto {
  id: number;
  /** 全局唯一标识；创建后不可改 */
  flagKey: string;
  label: string;
  description: string;
  /** 平台总开关 */
  enabled: boolean;
  sortOrder: number;
}

/** 平台工具目录项（只读，不可增删；仅平台总开关可改） */
export interface PlatformToolDto {
  id: number;
  /** 全局唯一，对齐 web/api ToolRegistry 的工具名 */
  toolKey: string;
  displayName: string;
  description: string;
  /** 分组：file/memory/session/agent/shell/web/code/knowledge/blackboard 等 */
  toolGroup: string;
  sortOrder: number;
  /** 平台总开关 */
  enabled: boolean;
}

/** 组织菜单可见性行：全量目录 × 该组织生效态（无行=默认可见） */
export interface OrgMenuSettingDto {
  menuId: number;
  menuKey: string;
  label: string;
  visible: boolean;
}

/** 组织功能开关启用行：全量目录 × 该组织生效态（无行=默认启用） */
export interface OrgFlagSettingDto {
  flagId: number;
  flagKey: string;
  label: string;
  enabled: boolean;
}

/** 组织工具启用行：全量目录 × 该组织生效态（无行=默认启用） */
export interface OrgToolSettingDto {
  toolId: number;
  toolKey: string;
  displayName: string;
  enabled: boolean;
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

// ============ 运维服务器目录（platformAdmin 维护归属与授权；spoke 按归属只读下发） ============

/** 运维服务器目录项；orgId 必填（组织必须），projectId 0=不限定项目（历史未归属行仅存量，新增必选组织） */
export interface OpsServerDto {
  id: number;
  /** spoke 侧稳定标识（全局唯一，创建后不可改） */
  serverKey: string;
  name: string;
  host: string;
  port: number;
  username: string;
  description: string;
  /** 分类/标签（创建必选；平台管理属性） */
  category: string;
  /** 系统类型（选填，空串=未填；随目录下发 spoke 供 AI 识别环境） */
  osType: string;
  sortOrder: number;
  enabled: boolean;
  /** 归属组织 id（组织必须，>0） */
  orgId: number;
  /** 归属项目 id；0=不限定项目 */
  projectId: number;
  /** 是否已设置登录密码（hub 加密保存、随目录下发 spoke；回显仅此布尔位，不含明文） */
  passwordSet: boolean;
}

/** 运维服务器用户时效授权（一人一服务器一条，重复提交=续期；过期行保留作历史） */
export interface OpsServerGrantDto {
  id: number;
  serverId: number;
  userId: number;
  /** 授权用户名（后端已解析，供展示） */
  userName: string;
  /** ISO-8601 时间字符串 */
  validFrom: string;
  validUntil: string;
  /** 操作人（platformAdmin）用户 id */
  grantedBy: number;
  /** 服务端判定：validUntil 已过=true */
  expired: boolean;
}

/** 运维服务器命令执行记录（spoke 上报 hub 落库；operator 由 hub 按 appkey 身份补全） */
export interface OpsCommandLogDto {
  id: number;
  serverKey: string;
  serverName: string;
  host: string;
  command: string;
  /** ai | user */
  source: string;
  operator: string;
  /** ISO-8601 时间字符串 */
  executedAt: string;
  createdAt: string;
}

/** 命令记录分页（后端 OpsCommandLogPageResponse：items/total/page/size，按 executed_at 倒序） */
export interface OpsCommandLogPage {
  items: OpsCommandLogDto[];
  total: number;
  /** 当前页码（0 起） */
  page: number;
  size: number;
}
