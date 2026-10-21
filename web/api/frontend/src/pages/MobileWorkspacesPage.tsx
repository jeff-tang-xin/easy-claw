import {useEffect, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {getJson} from '../api';
import {isIconUrl, useBranding} from '../branding';
import {useCloudConfig} from '../cloudConfig';
import '../mobile.css';

/** 移动端工作区摘要：只取列表展示所需字段（与 WorkspacesPage 的 WorkspaceSummary 对齐） */
interface WorkspaceSummary {
  workspaceId: string;
  name: string;
  description: string;
  path: string;
  /** 工作区形态分类：single / team / schedule（存量工作区后端兜底为 single） */
  type?: string;
}

/** 移动端入口定义：SOLO 滚动到下方工作区列表；运维/数据库直达固定内置工作区页面 */
interface MobileEntry {
  key: string;
  icon: string;
  label: string;
  desc: string;
  action: () => void;
}

/** 移动端工作区首页（/m）：手机 / pad 适配页面，只包含 SOLO、运维、数据库三类工作区。
 *  独立于桌面端 app-shell 布局（无侧边栏），由 App.tsx 按 /m 前缀切换渲染。
 *  运维/数据库入口仅在 cloud 模式展示——与桌面端侧边栏约定一致（local 模式无 hub 下发，
 *  两个页面拉不到服务器/连接清单，进去只是空页）。 */
export default function MobileWorkspacesPage() {
  const branding = useBranding();
  const cloudMode = useCloudConfig().cloudMode;
  const navigate = useNavigate();
  const [items, setItems] = useState<WorkspaceSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    getJson<WorkspaceSummary[]>('/api/workspaces')
      .then((list) => {
        setItems(list);
        setError('');
      })
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false));
  }, []);

  /** SOLO 工作区：存量数据 type 为 null 时按 single 兜底（与 WorkspacesPage 同口径） */
  const soloList = items.filter((w) => (w.type || 'single') === 'single');

  const entries: MobileEntry[] = [
    {
      key: 'solo',
      icon: '👤',
      label: 'SOLO',
      desc: 'AI 工作区',
      action: () => listRef.current?.scrollIntoView({behavior: 'smooth', block: 'start'}),
    },
    ...(cloudMode
      ? [
          {key: 'ops', icon: '🖥️', label: '运维', desc: '远程服务器', action: () => navigate('/m/ops')},
          {key: 'db', icon: '🗄️', label: '数据库', desc: '查询分析', action: () => navigate('/m/db')},
        ]
      : []),
  ];

  return (
    <div className="mws-page">
      <header className="mws-header">
        <div className="mws-logo">
          {isIconUrl(branding.icon) ? (
            <img src={branding.icon} alt={branding.name} />
          ) : (
            <span>{branding.icon}</span>
          )}
        </div>
        <div className="mws-brand">
          <span className="mws-title">{branding.name}</span>
          <span className="mws-sub">{branding.subtitle}</span>
        </div>
      </header>

      <div className="mws-entries">
        {entries.map((e) => (
          <button key={e.key} type="button" className="mws-entry" onClick={e.action}>
            <span className="mws-entry-icon">{e.icon}</span>
            <span className="mws-entry-label">{e.label}</span>
            <span className="mws-entry-desc">{e.desc}</span>
          </button>
        ))}
      </div>

      <div className="mws-section" ref={listRef}>
        <div className="mws-section-title">
          SOLO 工作区
          {!loading && !error && <span className="mws-count">{soloList.length}</span>}
        </div>
        {loading ? (
          <div className="mws-empty">加载中...</div>
        ) : error ? (
          <div className="mws-empty mws-empty-error">{error}</div>
        ) : soloList.length === 0 ? (
          <div className="mws-empty">
            还没有 SOLO 工作区
            <span className="mws-empty-hint">请在电脑端「工作区」页面创建后刷新</span>
          </div>
        ) : (
          <div className="mws-list">
            {soloList.map((ws) => (
              <button
                key={ws.workspaceId}
                type="button"
                className="mws-ws-card"
                onClick={() => navigate(`/m/chat/${ws.workspaceId}`)}
              >
                <span className="mws-ws-icon">👤</span>
                <span className="mws-ws-body">
                  <span className="mws-ws-name">{ws.name}</span>
                  <span className="mws-ws-desc">{ws.description || ws.path}</span>
                </span>
                <span className="mws-ws-arrow">›</span>
              </button>
            ))}
          </div>
        )}
      </div>

      <footer className="mws-footer">
        <button
          type="button"
          className="mws-desktop-link"
          onClick={() => navigate('/workspaces/single')}
        >
          电脑版完整界面 →
        </button>
      </footer>
    </div>
  );
}
