import {useEffect, useState} from 'react';
import {Navigate, NavLink, Route, Routes} from 'react-router-dom';
import {isIconUrl, loadBranding, useBranding} from './branding';
import WorkspacesPage from './pages/WorkspacesPage';
import ComingSoonPage from './pages/ComingSoonPage';
import ChatPage from './pages/ChatPage';
import SkillsPage from './pages/SkillsPage';
import ScenariosPage from './pages/ScenariosPage';
import ToolsPage from './pages/ToolsPage';
import McpPage from './pages/McpPage';
import BlackboardPage from './pages/BlackboardPage';
import KnowledgePage from './pages/KnowledgePage';
import SettingsPage from './pages/SettingsPage';
import './chatSocket';

type MenuItem = { to: string; icon: string; label: string; end?: boolean };
type MenuGroup = { title: string; items: MenuItem[] };

/** 侧边栏分组：工作区（按形态） / 工具管理 / 系统管理 */
const menuGroups: MenuGroup[] = [
  {
    title: '工作区',
    items: [
      { to: '/workspaces/single', icon: '👤', label: 'SOLO' },
      { to: '/workspaces/team', icon: '👥', label: '团队' },
      { to: '/workspaces/schedule', icon: '⏰', label: '定时' },
    ],
  },
  {
    title: '工具管理',
    items: [
      { to: '/scenarios', icon: '🎬', label: '场景' },
      { to: '/skills', icon: '📚', label: 'Skill' },
      { to: '/tools', icon: '🔧', label: '工具' },
      { to: '/mcp', icon: '🔌', label: 'MCP' },
    ],
  },
  {
    title: '系统管理',
    items: [
      { to: '/blackboard', icon: '🗒️', label: '记录本' },
      { to: '/knowledge', icon: '🧠', label: '知识库' },
      { to: '/settings', icon: '⚙️', label: '设置' },
    ],
  },
];

/** 侧边栏折叠状态的 localStorage 键：刷新后保持用户上次的选择 */
const SIDEBAR_COLLAPSED_KEY = 'easyclaw.sidebar.collapsed';

export default function App() {

  const branding = useBranding();
  // 启动时拉取品牌配置（name/subtitle/icon → logo、favicon、标签页标题）；失败静默用默认值
  useEffect(() => {
    void loadBranding();
  }, []);

  const [collapsed, setCollapsed] = useState(() => localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1');
  const toggleSidebar = () => {
    setCollapsed((c) => {
      const next = !c;
      localStorage.setItem(SIDEBAR_COLLAPSED_KEY, next ? '1' : '0');
      return next;
    });
  };

  return (
    <div className="app-shell">
      <aside className={'app-sidebar' + (collapsed ? ' collapsed' : '')}>
        <div className="app-logo">
          <div className="logo-icon">
            {isIconUrl(branding.icon) ? (
              <img className="logo-icon-img" src={branding.icon} alt={branding.name} />
            ) : (
              branding.icon
            )}
          </div>
          <div className="logo-text">
            <span className="logo-title">{branding.name}</span>
            <span className="logo-sub">{branding.subtitle}</span>
          </div>
        </div>
        <button
          type="button"
          className="sidebar-toggle"
          onClick={toggleSidebar}
          title={collapsed ? '展开菜单' : '收起菜单'}
        >
          {collapsed ? '»' : '«'}
        </button>
        <nav className="app-nav">
          {menuGroups.map((g) => (
            <div key={g.title} className="nav-group">
              <div className="nav-group-title">{g.title}</div>
              {g.items.map((m) => (
                <NavLink
                  key={m.to}
                  to={m.to}
                  end={m.end}
                  title={m.label}
                  className={({ isActive }) => 'nav-btn' + (isActive ? ' active' : '')}
                >
                  <span className="nav-icon">{m.icon}</span>
                  <span className="nav-label">{m.label}</span>
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-footer">Powered by xinl.tang</div>
      </aside>
      <main className="app-main">
        <Routes>
          <Route path="/" element={<Navigate to="/workspaces/single" replace />} />
          <Route path="/workspaces" element={<Navigate to="/workspaces/single" replace />} />
          {/* 团队 / 定时工作区详情标准尚未定稿：入口保留可点，详情页显示建设中占位，不挂载完整工作区管理 */}
          <Route path="/workspaces/team" element={
            <ComingSoonPage
              icon="👥"
              title="团队工作区"
              features={['多角色分工协作', '任务自动派发与汇总', '团队过程可观测']}
            />
          } />
          <Route path="/workspaces/schedule" element={
            <ComingSoonPage
              icon="⏰"
              title="定时工作区"
              features={['定时 / 周期任务调度', '执行结果自动留痕', '计划任务集中管理']}
            />
          } />
          <Route path="/workspaces/:wsType" element={<WorkspacesPage />} />
          <Route path="/chat/:workspaceId" element={<ChatPage />} />
          <Route path="/skills" element={<SkillsPage />} />
          <Route path="/scenarios" element={<ScenariosPage />} />
          <Route path="/tools" element={<ToolsPage />} />
          <Route path="/mcp" element={<McpPage />} />
          <Route path="/blackboard" element={<BlackboardPage />} />
          <Route path="/knowledge" element={<KnowledgePage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
      </main>
    </div>
  );
}
