import {useCallback, useEffect, useMemo, useState} from 'react';
import {Navigate, NavLink, Route, Routes, useLocation, useParams} from 'react-router-dom';
import {fetchMe, logout} from './api';
import {clearSession, getCurrentOrgId, loadSession, setCurrentOrgId} from './auth';
import type {MeResponse} from './types';
import Breadcrumb, {type Crumb} from './components/Breadcrumb';
import ChangePasswordCard from './components/ChangePasswordCard';
import ChangePasswordModal from './components/ChangePasswordModal';
import AppKeysPage from './pages/AppKeysPage';
import AuditLogsPage from './pages/AuditLogsPage';
import GatewayPage from './pages/GatewayPage';
import LoginPage from './pages/LoginPage';
import OrgDetailPage from './pages/OrgDetailPage';
import OrgsPage from './pages/OrgsPage';
import ProjectsPage from './pages/ProjectsPage';
import ProjectSpacePage from './pages/ProjectSpacePage';
import ProvidersPage from './pages/ProvidersPage';
import RolesPage from './pages/RolesPage';
import UsersPage from './pages/UsersPage';
import WorkspacesPage, {MenuConfigView} from './pages/WorkspacesPage';
import {NAV_GROUPS, ROLE_LABELS, groupVisible, itemVisible} from './nav';

const COLLAPSED_KEY = 'hub.sidebar.collapsed';

/** 侧边栏外壳 + 路由。会话状态集中在根部：me / 当前组织，页面通过 props 消费。 */
// ---------- 顶部面包屑（IA §0.2）：组织维度「{组织} / {页面}」，平台维度「平台 / {页面}」 ----------

/** 当前 orgId 对应组织名（me.orgs 含全部加入组织）；拿不到时回落「组织」。 */
function orgNameOf(me: MeResponse, orgId: number | null): string {
  if (orgId == null) return '组织';
  return me.orgs.find((o) => o.id === orgId)?.name ?? '组织';
}

function TopBreadcrumb({me, orgId}: {me: MeResponse; orgId: number | null}) {
  const loc = useLocation();
  const path = loc.pathname;

  // 项目空间自带「组织 / 项目 / tab」面包屑，不重复渲染。
  if (path.startsWith('/projects/')) return null;

  const orgName = orgNameOf(me, orgId);
  const items: Crumb[] = [];

  if (path === '/' || path.startsWith('/login')) return null;
  if (path === '/orgs') {
    // 组织管理入口：组织列表 + 当前组织成员
    items.push({text: '组织管理'});
  } else if (path.startsWith('/orgs/')) {
    // /orgs/:orgId 某组织成员管理；组织名以路由参数为准（可能非当前切换组织）
    const routeOrgId = Number(path.slice('/orgs/'.length).split('/')[0]);
    items.push({text: '组织管理'}, {text: orgNameOf(me, routeOrgId)}, {text: '成员'});
  } else if (path.startsWith('/projects')) {
    items.push({text: orgName}, {text: '项目'});
  } else if (path.startsWith('/appkeys')) {
    items.push({text: orgName}, {text: '我的 AppKey'});
  } else if (path.startsWith('/roles')) {
    items.push({text: orgName}, {text: '角色与权限'});
  } else if (path.startsWith('/audit')) {
    items.push({text: orgName}, {text: '审计'});
  } else if (path.startsWith('/gateway')) {
    items.push({text: orgName}, {text: 'LLM 网关'});
  } else if (path.startsWith('/workspaces')) {
    items.push({text: orgName}, {text: 'Spoke 工作区'});
  } else if (path.startsWith('/providers')) {
    items.push({text: '平台'}, {text: '模型 Provider'});
  } else if (path.startsWith('/users')) {
    items.push({text: '平台'}, {text: '用户管理'});
  }

  return <Breadcrumb items={items} />;
}

/** 菜单配置路由包装：从路径参数取工作区 id，非法则回工作区列表。 */
function MenuConfigRoute() {
  const {wid} = useParams();
  const id = Number(wid);
  if (!Number.isFinite(id) || id <= 0) return <Navigate to="/workspaces" replace />;
  return <MenuConfigView workspaceId={id} />;
}

export default function App() {
  const [booting, setBooting] = useState(true);
  const [me, setMe] = useState<MeResponse | null>(null);
  const [orgId, setOrgId] = useState<number | null>(getCurrentOrgId());
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem(COLLAPSED_KEY) === '1');
  // 密码临期提醒 → 自助改密弹窗
  const [pwdModalOpen, setPwdModalOpen] = useState(false);
  // 本次登录是否已关闭/处理过临期提醒（刷新页面前不再重复弹横幅文案，仅保留可忽略的内联条）
  const [pwdBannerDismissed, setPwdBannerDismissed] = useState(false);

  // 启动：有本地会话则拉 me 校准（同时校验 token 有效性与组织列表）
  useEffect(() => {
    if (!loadSession()) {
      setBooting(false);
      return;
    }
    fetchMe()
      .then((m) => {
        setMe(m);
        // 当前组织失效（被移出等）时回退到第一个组织
        if (m.orgs.length > 0 && !m.orgs.some((o) => o.id === getCurrentOrgId())) {
          setCurrentOrgId(m.orgs[0].id);
          setOrgId(m.orgs[0].id);
        }
      })
      .catch(() => clearSession())
      .finally(() => setBooting(false));
  }, []);

  const currentRole = useMemo(
    () => me?.orgs.find((o) => o.id === orgId)?.role ?? null,
    [me, orgId],
  );

  const switchOrg = useCallback((id: number) => {
    setCurrentOrgId(id);
    setOrgId(id);
  }, []);

  const reloadMe = useCallback(async () => {
    setMe(await fetchMe());
  }, []);

  const onLogout = useCallback(async () => {
    try {
      await logout();
    } catch {
      // 忽略登出失败，本地会话照样清
    }
    clearSession();
    setMe(null);
  }, []);

  const toggleCollapsed = useCallback(() => {
    setCollapsed((c) => {
      localStorage.setItem(COLLAPSED_KEY, c ? '0' : '1');
      return !c;
    });
  }, []);

  if (booting) {
    return <div className="boot-hint">加载中…</div>;
  }
  if (!me) {
    return (
      <LoginPage
        onLoggedIn={(m) => {
          setOrgId(getCurrentOrgId());
          setMe(m);
        }}
      />
    );
  }

  // 带 mcp 标记的会话（首登未改密就关了页面）：改密前只渲染强制改密卡片，不进主界面
  if (me.user.mustChangePassword) {
    return (
      <ChangePasswordCard
        usernameOrEmail={me.user.username}
        onDone={(m) => {
          setOrgId(getCurrentOrgId());
          setMe(m);
        }}
      />
    );
  }

  // 菜单显隐以 /api/me 的 permissions 为准（服务端对每个端点仍有强校验）
  const has = (p: string) => me.permissions.includes(p);
  const displayName = me.user.displayName || me.user.username;

  return (
    <div className="app-shell">
        <aside className={collapsed ? 'app-sidebar collapsed' : 'app-sidebar'}>
          <div className="app-logo">
            <div className="logo-icon">🐾</div>
            <div className="logo-text">
              <span className="logo-title">Easy-Claw Hub</span>
              <span className="logo-sub">控制台</span>
            </div>
          </div>
          <button
            type="button"
            className="sidebar-toggle"
            onClick={toggleCollapsed}
            title={collapsed ? '展开菜单' : '收起菜单'}
          >
            {collapsed ? '»' : '«'}
          </button>

          {me.orgs.length > 0 && (
            <div className="sidebar-org">
              <span className="nav-icon">🏢</span>
              <select
                className="org-switcher"
                value={orgId ?? ''}
                onChange={(e) => switchOrg(Number(e.target.value))}
                title="切换当前组织"
              >
                {me.orgs.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}
                  </option>
                ))}
              </select>
            </div>
          )}

          <nav className="app-nav">
            {NAV_GROUPS.filter((g) => groupVisible(g, has)).map((group) => (
              <div className="nav-group" key={group.title}>
                <div className="nav-group-title">{group.title}</div>
                {group.items.filter((item) => itemVisible(item, has)).map((item) => (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    className={({isActive}) => (isActive ? 'nav-btn active' : 'nav-btn')}
                    title={item.title}
                  >
                    <span className="nav-icon">{item.icon}</span>
                    <span className="nav-label">{item.label}</span>
                  </NavLink>
                ))}
              </div>
            ))}
          </nav>

          <div className="sidebar-user">
            <div className="sidebar-user-avatar">{displayName.charAt(0).toUpperCase()}</div>
            <div className="sidebar-user-info">
              <span className="sidebar-user-name">{displayName}</span>
              <span className="sidebar-user-role">
                {currentRole ? (ROLE_LABELS[currentRole] ?? currentRole) : '未加入组织'}
              </span>
            </div>
            <button type="button" className="sidebar-logout" onClick={() => void onLogout()} title="退出登录">
              ⏻
            </button>
          </div>
        </aside>

        <main className="app-main">
          <TopBreadcrumb me={me} orgId={orgId} />
          {me.user.passwordExpiringSoon && !pwdBannerDismissed && (
            <div className="pwd-expiry-banner" role="status">
              <span>
                ⚠️ 你的密码将于 {me.user.passwordExpiresAt ? new Date(me.user.passwordExpiresAt).toLocaleDateString() : '近期'}{' '}
                到期（有效期 60 天）。为避免到期后无法登录，请尽快修改密码。
              </span>
              <span className="pwd-expiry-actions">
                <button type="button" className="btn btn-primary btn-sm" onClick={() => setPwdModalOpen(true)}>
                  立即修改
                </button>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => setPwdBannerDismissed(true)}>
                  稍后
                </button>
              </span>
            </div>
          )}
          <Routes>
            <Route path="/" element={<Navigate to="/projects" replace />} />
            <Route path="/login" element={<Navigate to="/projects" replace />} />
            <Route
              path="/projects"
              element={<ProjectsPage orgId={orgId} role={currentRole} meUserId={me.user.id} onOrgsNeeded={reloadMe} />}
            />
            <Route
              path="/projects/:pid/*"
              element={<ProjectSpacePage orgs={me.orgs} meUserId={me.user.id} />}
            />
            <Route
              path="/orgs"
              element={<OrgsPage me={me} currentOrgId={orgId} onSwitchOrg={switchOrg} onChanged={reloadMe} />}
            />
            <Route path="/orgs/:orgId" element={<OrgDetailPage me={me} onChanged={reloadMe} />} />
            <Route path="/roles" element={<RolesPage role={currentRole} />} />
            <Route path="/audit" element={<AuditLogsPage orgId={orgId} role={currentRole} />} />
            <Route path="/appkeys" element={<AppKeysPage orgId={orgId} />} />
            <Route path="/gateway" element={<GatewayPage orgId={orgId} role={currentRole} />} />
            <Route path="/workspaces" element={<WorkspacesPage orgId={orgId} />} />
            <Route path="/workspaces/:wid/menus" element={<MenuConfigRoute />} />
            <Route path="/users" element={<UsersPage />} />
            <Route path="/providers" element={<ProvidersPage />} />
            <Route path="*" element={<Navigate to="/projects" replace />} />
          </Routes>
        </main>
        {pwdModalOpen && (
          <ChangePasswordModal
            onClose={() => setPwdModalOpen(false)}
            onChanged={(m) => {
              setMe(m);
              setPwdModalOpen(false);
              setPwdBannerDismissed(true);
            }}
          />
        )}
    </div>
  );
}
