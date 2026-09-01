import {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {del, getJson, postJson, putJson} from '../api';
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
interface ToolDef { name: string; displayName: string; requiresConfirm: boolean; }
interface ScenarioOption { id: number; name: string; displayName: string; icon?: string; active: boolean; }

/** 内置「通用编程」场景标识，与后端 SystemDataSeeder / WorkspaceController 保持一致 */
const DEFAULT_SCENARIO = 'general-coding';

export default function WorkspacesPage() {
  const [items, setItems] = useState<WorkspaceSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState('');
  const [path, setPath] = useState('');
  const [description, setDescription] = useState('');
  const [scenarioName, setScenarioName] = useState(DEFAULT_SCENARIO);
  const [scenarios, setScenarios] = useState<ScenarioOption[]>([]);
  const navigate = useNavigate();

  // 白名单管理弹窗状态
  const [permWsId, setPermWsId] = useState<string | null>(null);
  const [permRules, setPermRules] = useState<PermRule[]>([]);
  const [permTools, setPermTools] = useState<ToolDef[]>([]);

  // 编辑弹窗状态（仅名称/描述可改，路径不可变以免沙箱根目录漂移）
  const [editing, setEditing] = useState<WorkspaceSummary | null>(null);
  const [editName, setEditName] = useState('');
  const [editDesc, setEditDesc] = useState('');
  const [editScenario, setEditScenario] = useState(DEFAULT_SCENARIO);
  const [saving, setSaving] = useState(false);

  // 删除确认弹窗状态
  const [deleting, setDeleting] = useState<WorkspaceSummary | null>(null);
  const [removing, setRemoving] = useState(false);

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
    // 场景列表只在挂载时拉一次：新建/编辑弹窗都要用，且变动频率极低
    getJson<ScenarioOption[]>('/api/scenarios')
      .then((list) => setScenarios(list.filter((s) => s.active)))
      .catch(() => setScenarios([]));
  }, []);

  const create = async () => {
    try {
      await postJson('/api/workspaces', { name, description, path, scenarioName });
      setName('');
      setPath('');
      setDescription('');
      setScenarioName(DEFAULT_SCENARIO);
      setShowCreate(false);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const openEdit = (ws: WorkspaceSummary) => {
    setEditing(ws);
    setEditName(ws.name);
    setEditDesc(ws.description || '');
    // 回显当前绑定：查不到（未绑定/接口异常）时落到默认场景，不阻塞编辑
    setEditScenario(DEFAULT_SCENARIO);
    getJson<ScenarioOption | null>(`/api/scenarios/active/${ws.workspaceId}`)
      .then((s) => { if (s && s.name) setEditScenario(s.name); })
      .catch(() => undefined);
  };

  const saveEdit = async () => {
    if (!editing || !editName.trim()) return;
    setSaving(true);
    try {
      await putJson(`/api/workspaces/${editing.workspaceId}`, {
        name: editName.trim(),
        description: editDesc,
        scenarioName: editScenario,
      });
      setEditing(null);
      await load();
    } catch (e) {
      setError(String(e));
    } finally {
      setSaving(false);
    }
  };

  const confirmRemove = async () => {
    if (!deleting) return;
    setRemoving(true);
    try {
      await del(`/api/workspaces/${deleting.workspaceId}`);
      setDeleting(null);
      await load();
    } catch (e) {
      setError(String(e));
    } finally {
      setRemoving(false);
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
      // 只保留「调用时会弹确认」的工具：静默放行的只读工具本就不询问，
      // 列在授权面板里是噪声。requiresConfirm 由后端 ToolPermissionPolicy 判定。
      setPermTools(tools.filter((t) => t.requiresConfirm));
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
            <label>场景（决定 AI 在此工作区能做什么、怎么做）</label>
            <select value={scenarioName} onChange={(e) => setScenarioName(e.target.value)}>
              {scenarios.length === 0 && <option value={DEFAULT_SCENARIO}>💻 通用编程</option>}
              {scenarios.map((s) => (
                <option key={s.id} value={s.name}>
                  {s.icon ? `${s.icon} ` : ''}{s.displayName}
                </option>
              ))}
            </select>
            <div className="hint">创建后可随时在此页编辑切换</div>
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
            已永久授权 {permRules.length} / 共 {permTools.length} 个需授权工具；开启后调用时不再询问确认<br />
            只读工具（读文件、搜索、代码分析等）默认静默放行，不在此列出
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

      {editing && (
        <Modal
          title="✏️ 编辑工作区"
          subtitle="项目目录不可修改（避免 Agent 沙箱根目录漂移）"
          onClose={() => setEditing(null)}
          width={560}
        >
          <div className="field">
            <label>名称</label>
            <input value={editName} onChange={(e) => setEditName(e.target.value)} />
          </div>
          <div className="field">
            <label>项目目录（只读）</label>
            <input value={editing.path} disabled readOnly />
          </div>
          <div className="field">
            <label>场景（决定 AI 在此工作区能做什么、怎么做）</label>
            <select value={editScenario} onChange={(e) => setEditScenario(e.target.value)}>
              {scenarios.length === 0 && <option value={DEFAULT_SCENARIO}>💻 通用编程</option>}
              {scenarios.map((s) => (
                <option key={s.id} value={s.name}>
                  {s.icon ? `${s.icon} ` : ''}{s.displayName}
                </option>
              ))}
            </select>
            <div className="hint">切换后立即重建该工作区的 AI，正在进行的对话建议先结束</div>
          </div>
          <div className="field">
            <label>描述（可选）</label>
            <textarea value={editDesc} onChange={(e) => setEditDesc(e.target.value)} rows={2} />
          </div>
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
            <button className="btn" onClick={() => setEditing(null)}>取消</button>
            <button className="btn primary" disabled={saving || !editName.trim()} onClick={saveEdit}>
              {saving ? '保存中…' : '保存'}
            </button>
          </div>
        </Modal>
      )}

      {deleting && (
        <Modal
          title="🗑 删除工作区"
          subtitle={deleting.name}
          onClose={() => setDeleting(null)}
          width={520}
        >
          <div className="hint" style={{ lineHeight: 1.8, marginBottom: 12 }}>
            确定删除工作区 <strong>{deleting.name}</strong>？将同时清理：
            <ul style={{ margin: '8px 0 0', paddingLeft: 20 }}>
              <li>工作区记录与内存中的 Agent</li>
              <li>全部会话记录</li>
              <li>场景激活关系与工具白名单</li>
            </ul>
            <div style={{ marginTop: 10 }}>
              磁盘目录 <code>{deleting.path}</code> 不会被删除，你的项目文件是安全的。
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
            <button className="btn" onClick={() => setDeleting(null)}>取消</button>
            <button className="btn danger" disabled={removing} onClick={confirmRemove}>
              {removing ? '删除中…' : '确认删除'}
            </button>
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
                  title="编辑工作区"
                  onClick={(e) => { e.stopPropagation(); openEdit(ws); }}
                >
                  ✏️
                </button>
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
                  onClick={(e) => { e.stopPropagation(); setDeleting(ws); }}
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
