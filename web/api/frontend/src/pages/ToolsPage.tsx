import {useEffect, useState} from 'react';
import {getJson, putJson} from '../api';

interface ToolDef {
  id: number; name: string; displayName: string; description: string;
  toolGroup: string; enabled: boolean; params: string;
}

export default function ToolsPage() {
  const [items, setItems] = useState<ToolDef[]>([]);
  const [error, setError] = useState('');
  const [builtin, setBuiltin] = useState<{ name: string; description: string }[]>([]);

  const load = async () => {
    try {
      setItems(await getJson<ToolDef[]>('/api/tools'));
      setBuiltin(await getJson('/api/tools/builtin'));
    } catch (e) {
      setError(String(e));
    }
  };
  useEffect(() => { load(); }, []);

  const toggle = async (t: ToolDef) => {
    try {
      await putJson(`/api/tools/${t.id}/enabled/${!t.enabled}`, {});
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const enabledNames = new Set(items.filter((t) => t.enabled).map((t) => t.name));

  return (
    <div className="page">
      <h1 className="page-title">🔧 工具管理</h1>
      {error && <div className="error-box">{error}</div>}

      <div className="card" style={{ marginBottom: 16 }}>
        <h3 style={{ marginTop: 0 }}>内置工具（{builtin.length}）</h3>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {builtin.map((b) => (
            <span key={b.name} className={`badge ${enabledNames.has(b.name) ? 'green' : 'gray'}`} title={b.description}>
              {b.name}
            </span>
          ))}
        </div>
      </div>

      <div className="card">
        <table>
          <thead>
            <tr><th>工具</th><th>说明</th><th>分组</th><th>状态</th><th>操作</th></tr>
          </thead>
          <tbody>
            {items.map((t) => (
              <tr key={t.id}>
                <td style={{ fontWeight: 600 }}>{t.displayName || t.name}</td>
                <td className="hint">{t.description}</td>
                <td><span className="badge blue">{t.toolGroup}</span></td>
                <td>{t.enabled ? <span className="badge green">启用</span> : <span className="badge red">禁用</span>}</td>
                <td>
                  <button className="btn small" onClick={() => toggle(t)}>
                    {t.enabled ? '禁用' : '启用'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
