import { Link } from 'react-router-dom';

interface ComingSoonPageProps {
  /** 侧边栏入口对应的模式类型，仅用于文案 */
  icon: string;
  title: string;
  /** 建设中卡片里展示的功能要点 */
  features: string[];
}

/**
 * 团队 / 定时工作区的标准「建设中」占位页。
 * 侧边栏入口保留可点击，但完整工作区管理功能尚未开放，
 * 因此在路由层直接渲染本页，不挂载 WorkspacesPage（也不会发起列表请求）。
 */
export default function ComingSoonPage({ icon, title, features }: ComingSoonPageProps) {
  return (
    <div className="page">
      <h1 className="page-title">{icon} {title}</h1>

      <div className="coming-soon">
        <div className="coming-soon-icon">{icon}</div>
        <h2 className="coming-soon-title">功能建设中</h2>
        <p className="coming-soon-desc">
          {title}能力正在紧锣密鼓地开发中，敬请期待。
        </p>
        <ul className="coming-soon-features">
          {features.map((f) => (
            <li key={f}>{f}</li>
          ))}
        </ul>
        <Link className="btn primary coming-soon-btn" to="/workspaces/single">
          前往单人工作区
        </Link>
      </div>
    </div>
  );
}
