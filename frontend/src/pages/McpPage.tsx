import {useEffect, useState} from 'react';
import {del, getJson, postJson, putJson} from '../api';
import Modal from '../components/Modal';

interface McpService {
  id: number; name: string; type: string; command: string; url: string;
  enabled: boolean;
}

export default function McpPage() {
  const [items, setItems] = useState<McpService[]>([]);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<McpService | null>(null);

  const load = async () => {
    try {
      setItems(await getJson<McpService[]>('/api/mcp'));
    } catch (e) {
      setError(String(e));
    }
  };
  useEffect(() => { load(); }, []);

  const save = async () => {
    if (!editing) return;
    try {
      if (editing.id) await putJson(`/api/mcp/${editing.id}`, editing);
      else await postJson('/api/mcp', editing);
      setEditing(null);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const remove = async (id: number) => {
    if (!confirm('删除该 MCP 服务？')) return;
    try {
      await del(`/api/mcp/${id}`);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const connect = async (id: number) => {
    try {
      await postJson(`/api/mcp/${id}/connect`, {});
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  return (
    <div className="page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1 className="page-title">🔌 MCP 服务管理</h1>
        <button className="btn primary" onClick={() => setEditing({ id: 0, name: '', type: 'stdio', command: '', url: '', enabled: true })}>＋ 添加服务</button>
      </div>
      {error && <div className="error-box">{error}</div>}

      {editing && (
        <Modal title={editing.id ? '🔌 编辑 MCP 服务' : '🔌 添加 MCP 服务'} onClose={() => setEditing(null)} width={560}>
          <div className="field">
            <label>名称</label>
            <input value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} />
          </div>
          <div className="field">
            <label>类型</label>
            <select value={editing.type} onChange={(e) => setEditing({ ...editing, type: e.target.value })}>
              <option value="stdio">stdio（本地命令）</option>
              <option value="http">HTTP/SSE（远程）</option>
            </select>
          </div>
          {editing.type === 'stdio' ? (
            <div className="field">
              <label>启动命令</label>
              <input value={editing.command} onChange={(e) => setEditing({ ...editing, command: e.target.value })} placeholder="npx -y @modelcontextprotocol/server-filesystem" />
            </div>
          ) : (
            <div className="field">
              <label>服务 URL</label>
              <input value={editing.url} onChange={(e) => setEditing({ ...editing, url: e.target.value })} />
            </div>
          )}
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
            <button className="btn" onClick={() => setEditing(null)}>取消</button>
            <button className="btn primary" onClick={save}>保存</button>
          </div>
        </Modal>
      )}

      <div className="card">
        <table>
          <thead>
            <tr><th>名称</th><th>类型</th><th>连接</th><th>状态</th><th></th></tr>
          </thead>
          <tbody>
            {items.map((m) => (
              <tr key={m.id}>
                <td style={{ fontWeight: 600 }}>{m.name}</td>
                <td><span className="badge blue">{m.type}</span></td>
                <td className="hint" style={{ fontSize: 11 }}>{m.command || m.url}</td>
                <td>{m.enabled ? <span className="badge green">已连接</span> : <span className="badge gray">未连接</span>}</td>
                <td>
                  <button className="btn small" onClick={() => connect(m.id)}>连接</button>{' '}
                  <button className="btn small" onClick={() => setEditing({ ...m })}>编辑</button>{' '}
                  <button className="btn danger small" onClick={() => remove(m.id)}>删除</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
