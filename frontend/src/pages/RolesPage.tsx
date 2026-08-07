import {useEffect, useState} from 'react';
import {del, getJson, postJson, putJson} from '../api';
import Modal from '../components/Modal';

interface Role {
  id: number; name: string; displayName: string; role: string;
  goal: string; backstory: string; model: string; temperature: number; active: boolean;
}

export default function RolesPage() {
  const [items, setItems] = useState<Role[]>([]);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<Role | null>(null);

  const load = async () => {
    try {
      setItems(await getJson<Role[]>('/api/roles'));
    } catch (e) {
      setError(String(e));
    }
  };
  useEffect(() => { load(); }, []);

  const save = async () => {
    if (!editing) return;
    try {
      if (editing.id) await putJson(`/api/roles/${editing.id}`, editing);
      else await postJson('/api/roles', editing);
      setEditing(null);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const remove = async (id: number) => {
    if (!confirm('删除该角色？')) return;
    try {
      await del(`/api/roles/${id}`);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const toggleActive = async (r: Role) => {
    await postJson(`/api/roles/${r.id}/active/${!r.active}`, {});
    await load();
  };

  return (
    <div className="page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1 className="page-title">🎭 角色管理</h1>
        <button className="btn primary" onClick={() => setEditing({ id: 0, name: '', displayName: '', role: '', goal: '', backstory: '', model: '', temperature: 0.4, active: true })}>＋ 新建角色</button>
      </div>
      {error && <div className="error-box">{error}</div>}

      {editing && (
        <Modal title={editing.id ? '🎭 编辑角色' : '🎭 新建角色'} onClose={() => setEditing(null)} width={640}>
          <div style={{ display: 'flex', gap: 12 }}>
            <div className="field" style={{ flex: 1 }}>
              <label>名称（英文标识）</label>
              <input value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} placeholder="如：code-reviewer" />
            </div>
            <div className="field" style={{ flex: 1 }}>
              <label>显示名</label>
              <input value={editing.displayName} onChange={(e) => setEditing({ ...editing, displayName: e.target.value })} />
            </div>
          </div>
          <div className="field">
            <label>角色定位</label>
            <input value={editing.role} onChange={(e) => setEditing({ ...editing, role: e.target.value })} />
          </div>
          <div className="field">
            <label>目标</label>
            <input value={editing.goal} onChange={(e) => setEditing({ ...editing, goal: e.target.value })} />
          </div>
          <div className="field">
            <label>背景设定</label>
            <textarea value={editing.backstory} onChange={(e) => setEditing({ ...editing, backstory: e.target.value })} rows={3} />
          </div>
          <div style={{ display: 'flex', gap: 12 }}>
            <div className="field" style={{ flex: 1 }}>
              <label>模型（留空 = 全局默认，如 deepseek:deepseek-chat）</label>
              <input value={editing.model} onChange={(e) => setEditing({ ...editing, model: e.target.value })} placeholder="deepseek:deepseek-chat" />
            </div>
            <div className="field" style={{ width: 140 }}>
              <label>温度</label>
              <input type="number" step="0.1" min="0" max="2" value={editing.temperature} onChange={(e) => setEditing({ ...editing, temperature: Number(e.target.value) })} />
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
            <button className="btn" onClick={() => setEditing(null)}>取消</button>
            <button className="btn primary" onClick={save}>保存</button>
          </div>
        </Modal>
      )}

      <div className="card">
        <table>
          <thead>
            <tr><th>名称</th><th>定位</th><th>模型</th><th>温度</th><th>状态</th><th></th></tr>
          </thead>
          <tbody>
            {items.map((r) => (
              <tr key={r.id}>
                <td style={{ fontWeight: 600 }}>{r.displayName || r.name}</td>
                <td className="hint">{r.role}</td>
                <td><code style={{ fontSize: 12 }}>{r.model || '全局默认'}</code></td>
                <td>{r.temperature}</td>
                <td>
                  <button className="btn small" onClick={() => toggleActive(r)}>
                    {r.active ? <span className="badge green">启用</span> : <span className="badge gray">停用</span>}
                  </button>
                </td>
                <td>
                  <button className="btn small" onClick={() => setEditing({ ...r })}>编辑</button>{' '}
                  <button className="btn danger small" onClick={() => remove(r.id)}>删除</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="hint" style={{ marginTop: 12 }}>
        「主智能体」角色（name=main）的模型决定主控 Agent 的模型；子 Agent 通过 role 关联角色模型。
      </p>
    </div>
  );
}
