import {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {del, getJson, postJson} from '../api';
import Modal from '../components/Modal';

interface WorkspaceSummary {
  workspaceId: string;
  name: string;
  description: string;
  path: string;
  status: string;
  createdAt: string;
}

interface PermRule { id: number; toolName: string; createdAt: string; }
interface ToolDef { name: string; displayName: string; }

export default function WorkspacesPage() {
  const [items, setItems] = useState<WorkspaceSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState('');
  const [path, setPath] = useState('');
  const [description, setDescription] = useState('');
  const navigate = useNavigate();

  // 白名单管理弹窗状态
  const [permWsId, setPermWsId] = useState<string | null>(null);
  const [permRules, setPermRules] = useState<PermRule[]>([]);
  const [permTools, setPermTools] = useState<ToolDef[]>([]);

  const load = async () => {
    try {
      setItems(await getJson<WorkspaceSummary[]>('/api/workspaces'));
      setError('');
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const create = async () => {
    try {
      await postJson('/api/workspaces', { name, description, path });
      setName('');
      setPath('');
      setDescription('');
      setShowCreate(false);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const remove = async (id: string) => {
    if (!confirm('确定删除该工作区？会删除其 Agent 状态与会话记录。')) return;
    try {
      await del(`/api/workspaces/${id}`);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const openPerm = async (wid: string) => {
    setPermWsId(wid);
    try {
      const [rules, tools] = await Promise.all([
        getJson<PermRule[]>(`/api/workspaces/${wid}/permissions`),
        getJson<ToolDef[]>('/api/tools/builtin'),
      ]);
      setPermRules(rules);
      setPermTools(tools);
    } catch (e) {
      setError(String(e));
    }
  };

  const closePerm = () => {
    setPermWsId(null);
    setPermRules([]);
    setPermTools([]);
  };

  const browseDir = async () => {
    try {
      const dir: string | null = await postJson('/api/system/choose-dir', { startDir: path || '' });
      if (!dir) return;
      setPath(dir);
      if (!name) setName(dir.split(/[/\\]/).pop() || dir);
    } catch (e: any) {
      setError('选择目录失败: ' + (e?.message || e));
    }
  };

  const togglePerm = async (toolName: string) => {
    if (!permWsId) return;
    const inList = permRules.some((r) => r.toolName === toolName);
    if (inList) {
      await del(`/api/workspaces/${permWsId}/permissions/${encodeURIComponent(toolName)}`);
    } else {
      await postJson(`/api/workspaces/${permWsId}/permissions/${encodeURIComponent(toolName)}`, {});
    }
    const rules = await getJson<PermRule[]>(`/api/workspaces/${permWsId}/permissions`);
    setPermRules(rules);
  };

  return (
    <div className="page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1 className="page-title">📁 工作区</h1>
        <button className="btn primary" onClick={() => setShowCreate(!showCreate)}>
          {showCreate ? '取消' : '＋ 新建工作区'}
        </button>
      </div>

      {error && <div className="error-box">{error}</div>}

      {showCreate && (
        <Modal title="📁 新建工作区" onClose={() => setShowCreate(false)} width={560}>
          <div className="field">
            <label>名称</label>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="如：我的 Rust 项目" />
          </div>
          <div className="field">
            <label>项目目录（AI 只能在此目录内工作）</label>
            <div style={{ display: 'flex', gap: 8 }}>
              <input
                value={path}
                onChange={(e) => setPath(e.target.value)}
                placeholder="如：F:\rust\Easy-Copy"
                style={{ flex: 1 }}
              />
              <button className="btn" onClick={browseDir}>
                📁 浏览
              </button>
            </div>
          </div>
          <div className="field">
            <label>描述（可选）</label>
            <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={2} />
          </div>
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
            <button className="btn" onClick={() => setShowCreate(false)}>取消</button>
            <button className="btn primary" onClick={create}>创建并初始化</button>
          </div>
        </Modal>
      )}

      {permWsId && (
        <Modal
          title={`🔐 工具白名单管理`}
          onClose={closePerm}
          width={600}
          subtitle={items.find((w) => w.workspaceId === permWsId)?.name || ''}
        >
          <div style={{ marginBottom: 8 }} className="hint">
            已永久授权 {permRules.length} / 共 {permTools.length} 个工具；开启后调用时不再询问确认
          </div>
          <div style={{ maxHeight: 400, overflowY: 'auto', border: '1px solid var(--border)', borderRadius: 8 }}>
            {permTools.map((t) => {
              const on = permRules.some((r) => r.toolName === t.name);
              return (
                <div
                  key={t.name}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 10,
                    padding: '10px 14px', borderBottom: '1px solid var(--border)',
                  }}
                >
                  <span style={{ flex: 1, fontSize: 14, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={t.name}>
                    <strong>{t.displayName || t.name}</strong>
                    <span className="hint" style={{ marginLeft: 6 }}>({t.name})</span>
                  </span>
                  <label className={`toggle ${on ? 'on' : ''}`} onClick={() => togglePerm(t.name)}>
                    <span className="toggle-thumb" />
                  </label>
                </div>
              );
            })}
            {permTools.length === 0 && <div className="hint" style={{ padding: 16 }}>加载中...</div>}
          </div>
          <div style={{ marginTop: 12, display: 'flex', justifyContent: 'flex-end' }}>
            <button className="btn" onClick={closePerm}>完成</button>
          </div>
        </Modal>
      )}

      {loading ? (
        <div className="empty">加载中...</div>
      ) : items.length === 0 ? (
        <div className="empty">
          <h3>还没有工作区</h3>
          <p className="hint">点击右上角「新建工作区」，指定一个项目目录后即可与 AI 对话</p>
        </div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
          {items.map((ws) => (
            <div key={ws.workspaceId} className="card" style={{ cursor: 'pointer' }} onClick={() => navigate(`/chat/${ws.workspaceId}`)}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span style={{ fontSize: '1.6em' }}>📁</span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 700 }}>{ws.name}</div>
                  <div className="hint" style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {ws.path}
                  </div>
                </div>
                <button
                  className="btn small"
                  title="工具白名单"
                  onClick={(e) => { e.stopPropagation(); openPerm(ws.workspaceId); }}
                >
                  🔐
                </button>
                <button
                  className="btn danger small"
                  title="删除工作区"
                  onClick={(e) => { e.stopPropagation(); remove(ws.workspaceId); }}
                >
                  🗑
                </button>
              </div>
              {ws.description && <div className="hint" style={{ marginTop: 8 }}>{ws.description}</div>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
