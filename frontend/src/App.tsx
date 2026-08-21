import {Navigate, NavLink, Route, Routes} from 'react-router-dom';
import WorkspacesPage from './pages/WorkspacesPage';
import ChatPage from './pages/ChatPage';
import SkillsPage from './pages/SkillsPage';
import RolesPage from './pages/RolesPage';
import ScenariosPage from './pages/ScenariosPage';
import ToolsPage from './pages/ToolsPage';
import McpPage from './pages/McpPage';
import SettingsPage from './pages/SettingsPage';
import './chatSocket';

const menu = [
  { to: '/workspaces', icon: '📁', label: '工作区' },
  { to: '/scenarios', icon: '🎬', label: '场景' },
  { to: '/skills', icon: '📚', label: 'Skills' },
  { to: '/roles', icon: '🎭', label: '角色' },
  { to: '/tools', icon: '🔧', label: '工具' },
  { to: '/mcp', icon: '🔌', label: 'MCP' },
  { to: '/settings', icon: '⚙️', label: '设置' },
];

export default function App() {

  return (
    <div className="app-shell">
      <aside className="app-sidebar">
        <div className="app-logo">
          <div className="logo-icon">🦞</div>
          <div className="logo-text">
            <span className="logo-title">Easy-Claw</span>
            <span className="logo-sub">AI 编程助手</span>
          </div>
        </div>
        <nav className="app-nav">
          {menu.map((m) => (
            <NavLink
              key={m.to}
              to={m.to}
              className={({ isActive }) => 'nav-btn' + (isActive ? ' active' : '')}
            >
              <span className="nav-icon">{m.icon}</span>
              <span>{m.label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-footer">AgentScope 2.0</div>
      </aside>
      <main className="app-main">
        <Routes>
          <Route path="/" element={<Navigate to="/workspaces" replace />} />
          <Route path="/workspaces" element={<WorkspacesPage />} />
          <Route path="/chat/:workspaceId" element={<ChatPage />} />
          <Route path="/skills" element={<SkillsPage />} />
          <Route path="/scenarios" element={<ScenariosPage />} />
          <Route path="/roles" element={<RolesPage />} />
          <Route path="/tools" element={<ToolsPage />} />
          <Route path="/mcp" element={<McpPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
      </main>
    </div>
  );
}
