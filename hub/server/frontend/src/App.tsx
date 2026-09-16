import {useCallback, useEffect, useMemo, useState} from 'react';
import {Navigate, NavLink, Route, Routes} from 'react-router-dom';
import {fetchMe, logout} from './api';
import {clearSession, getCurrentOrgId, loadSession, setCurrentOrgId} from './auth';
import type {MeResponse} from './types';
import ChangePasswordCard from './components/ChangePasswordCard';
import AppKeysPage from './pages/AppKeysPage';
import AuditLogsPage from './pages/AuditLogsPage';
import GatewayPage from './pages/GatewayPage';
import LoginPage from './pages/LoginPage';
import OrgDetailPage from './pages/OrgDetailPage';
import OrgsPage from './pages/OrgsPage';
import ProjectsPage from './pages/ProjectsPage';
import ProjectSpacePage from './pages/ProjectSpacePage';
import ProvidersPage from './pages/ProvidersPage';
import UsersPage from './pages/UsersPage';

const ROLE_LABELS: Record<string, string> = {
  owner: '所有者',
  admin: '管理员',
  member: '成员',
  guest: '访客',
};

const COLLAPSED_KEY = 'hub.sidebar.collapsed';

/** 侧边栏外壳 + 路由。会话状态集中在根部：me / 当前组织，页面通过 props 消费。 */
export default function App() {
  const [booting, setBooting] = useState(true);
  const [me, setMe] = useState<MeResponse | null>(null);
  const [orgId, setOrgId] = useState<number | null>(getCurrentOrgId());
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem(COLLAPSED_KEY) === '1');

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
            <div className="nav-group">
              <div className="nav-group-title">工作</div>
              <NavLink to="/projects" className={({isActive}) => (isActive ? 'nav-btn active' : 'nav-btn')} title="项目">
                <span className="nav-icon">📁</span>
                <span className="nav-label">项目</span>
              </NavLink>
            </div>
            <div className="nav-group">
              <div className="nav-group-title">管理</div>
              <NavLink to="/orgs" className={({isActive}) => (isActive ? 'nav-btn active' : 'nav-btn')} title="组织">
                <span className="nav-icon">🏛️</span>
                <span className="nav-label">组织</span>
              </NavLink>
              {has('audit.read') && (
                <NavLink to="/audit" className={({isActive}) => (isActive ? 'nav-btn active' : 'nav-btn')} title="审计日志">
                  <span className="nav-icon">🛡️</span>
                  <span className="nav-label">审计日志</span>
                </NavLink>
              )}
              {has('appkey.manage') && (
                <NavLink to="/appkeys" className={({isActive}) => (isActive ? 'nav-btn active' : 'nav-btn')} title="AppKey">
                  <span className="nav-icon">🔑</span>
                  <span className="nav-label">AppKey</span>
                </NavLink>
              )}
              {has('audit.read') && (
                <NavLink to="/gateway" className={({isActive}) => (isActive ? 'nav-btn active' : 'nav-btn')} title="LLM 网关">
                  <span className="nav-icon">🌐</span>
                  <span className="nav-label">LLM 网关</span>
                </NavLink>
              )}
            </div>
            {(has('user.manage') || has('provider.manage')) && (
              <div className="nav-group">
                <div className="nav-group-title">平台</div>
                {has('user.manage') && (
                  <NavLink to="/users" className={({isActive}) => (isActive ? 'nav-btn active' : 'nav-btn')} title="用户管理">
                    <span className="nav-icon">👥</span>
                    <span className="nav-label">用户管理</span>
                  </NavLink>
                )}
                {has('provider.manage') && (
                  <NavLink to="/providers" className={({isActive}) => (isActive ? 'nav-btn active' : 'nav-btn')} title="Provider">
                    <span className="nav-icon">🧩</span>
                    <span className="nav-label">Provider</span>
                  </NavLink>
                )}
              </div>
            )}
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
            <Route path="/audit" element={<AuditLogsPage orgId={orgId} role={currentRole} />} />
            <Route path="/appkeys" element={<AppKeysPage orgId={orgId} />} />
            <Route path="/gateway" element={<GatewayPage orgId={orgId} role={currentRole} />} />
            <Route path="/users" element={<UsersPage />} />
            <Route path="/providers" element={<ProvidersPage />} />
            <Route path="*" element={<Navigate to="/projects" replace />} />
          </Routes>
        </main>
    </div>
  );
}
