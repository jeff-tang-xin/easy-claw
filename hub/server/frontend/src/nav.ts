/**
 * 侧边栏导航的单一数据源：菜单分组 + 每个菜单可见所需的权限码。
 * 侧边栏（App.tsx）与「角色与权限」矩阵页（RolesPage）都从这里取数，
 * 保证「菜单 → 权限 → 角色」的关联只有一份定义，不随 Permissions 改动而两边漂移。
 */

/** 单个菜单项。anyOfPerms 为空数组表示所有登录用户可见；多个为「任一满足即可」。 */
export interface NavItem {
  to: string;
  label: string;
  icon: string;
  title: string;
  anyOfPerms: string[];
  /** 平台级菜单：仅由用户的 platformAdmin 标志叠加权限门控（非组织角色授予）。 */
  platform?: boolean;
}

export interface NavGroup {
  title: string;
  items: NavItem[];
  /** 整组还需满足的权限（任一即可）；不给则只看组内是否有可见项。 */
  anyOfPerms?: string[];
  platform?: boolean;
}

export const NAV_GROUPS: NavGroup[] = [
  {
    title: '工作',
    items: [
      {to: '/projects', label: '项目', icon: '📁', title: '项目', anyOfPerms: []},
      {to: '/appkeys', label: '我的 AppKey', icon: '🔑', title: '我的 AppKey', anyOfPerms: ['appkey.self']},
    ],
  },
  {
    title: '组织资源',
    items: [
      {to: '/gateway', label: 'LLM 网关', icon: '🌐', title: 'LLM 网关', anyOfPerms: ['audit.read']},
      {to: '/audit', label: '审计日志', icon: '🛡️', title: '审计日志', anyOfPerms: ['audit.read']},
      {to: '/menus', label: '菜单可见性', icon: '📋', title: '组织菜单可见性', anyOfPerms: ['org.read']},
      {to: '/feature-flags', label: '开关与工具', icon: '🎚️', title: '组织功能开关与工具启用', anyOfPerms: ['org.read']},
    ],
  },
  {
    title: '组织管理',
    items: [
      {to: '/orgs', label: '组织', icon: '🏛️', title: '组织', anyOfPerms: []},
      {to: '/roles', label: '角色与权限', icon: '🔐', title: '角色与权限', anyOfPerms: ['org.manage']},
    ],
  },
  {
    title: '平台',
    anyOfPerms: ['user.manage', 'provider.manage', 'platform.catalog.manage'],
    platform: true,
    items: [
      {to: '/users', label: '用户管理', icon: '👥', title: '用户管理', anyOfPerms: ['user.manage'], platform: true},
      {to: '/providers', label: 'Provider', icon: '🧩', title: 'Provider', anyOfPerms: ['provider.manage'], platform: true},
      {to: '/platform-catalog', label: '平台目录', icon: '🗂️', title: '平台目录（菜单/开关/工具）', anyOfPerms: ['platform.catalog.manage'], platform: true},
    ],
  },
];

/** 组织角色中文标签（与后端 Permissions.ORG_ROLES 顺序一致）。 */
export const ROLE_LABELS: Record<string, string> = {
  owner: '所有者',
  admin: '管理员',
  member: '成员',
  guest: '访客',
};

/** 权限码中文说明；矩阵页展示用。新增权限码时在此补标签（后端 Permissions 为权威来源）。 */
export const PERMISSION_LABELS: Record<string, string> = {
  'org.read': '查看组织',
  'org.manage': '管理组织与成员',
  'org.delete': '删除组织',
  'member.manage': '管理成员',
  'project.read': '查看项目',
  'project.write': '创建 / 编辑项目',
  'asset.write': '写入项目内容',
  'appkey.self': '管理自己的 AppKey',
  'appkey.manage': '治理组织内全部 AppKey',
  'audit.read': '查看网关与审计日志',
  'provider.manage': '管理模型 Provider',
  'user.manage': '管理平台用户',
  'platform.catalog.manage': '管理平台目录（菜单/开关/工具）',
};

type HasPerm = (perm: string) => boolean;

/** 单个菜单是否可见：无权限要求恒可见；否则任一权限满足即可。 */
export const itemVisible = (item: NavItem, has: HasPerm): boolean =>
  item.anyOfPerms.length === 0 || item.anyOfPerms.some(has);

/** 整组是否可见：先过组级权限，再看组内是否至少有一个可见菜单。 */
export const groupVisible = (group: NavGroup, has: HasPerm): boolean => {
  if (group.anyOfPerms && !group.anyOfPerms.some(has)) return false;
  return group.items.some((item) => itemVisible(item, has));
};
