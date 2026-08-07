import { useEffect, useState } from 'react';
import { getJson, putJson } from '../api';

interface SettingsResponse {
  yaml: string;
  settingsFile: string;
  hotReloadNote: string;
}

export default function SettingsPage() {
  const [yaml, setYaml] = useState('');
  const [settingsFile, setSettingsFile] = useState('');
  const [hotReloadNote, setHotReloadNote] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');
  const [dirty, setDirty] = useState(false);

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const res = await getJson<SettingsResponse>('/api/settings');
      setYaml(res.yaml);
      setSettingsFile(res.settingsFile);
      setHotReloadNote(res.hotReloadNote);
      setDirty(false);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const save = async () => {
    setSaving(true);
    setError('');
    try {
      const saved = await putJson<SettingsResponse>('/api/settings', { yaml });
      setYaml(saved.yaml);
      setDirty(false);
      setToast('✅ 已保存，热生效配置已应用');
      setTimeout(() => setToast(''), 2500);
    } catch (e) {
      setError('保存失败: ' + e);
    } finally {
      setSaving(false);
    }
  };

  const hasPort = /^\s*server\s*:\s*$/m.test(yaml) && /^\s*port\s*:\s*\d+/m.test(yaml);

  return (
    <div className="page settings-page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h1 className="page-title">⚙️ 配置文件编辑</h1>
          <div className="hint">
            直接编辑 <code>{settingsFile || '~/.easyClaw/application.yml'}</code>
            {dirty && <span style={{ color: '#f59e0b', marginLeft: 8 }}>● 未保存</span>}
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {toast && <span className="toast-ok">{toast}</span>}
          <button className="btn" onClick={load} disabled={saving}>🔄 重置</button>
          <button className="btn primary" onClick={save} disabled={saving || !dirty}>
            {saving ? '保存中...' : '💾 保存'}
          </button>
        </div>
      </div>

      {error && <div className="error-box">{error}</div>}

      <div className="info-box" style={{ marginBottom: 12 }}>
        💡 {hotReloadNote || '保存后配置立即生效'}
      </div>

      {loading ? (
        <div className="hint">加载中...</div>
      ) : (
        <textarea
          className="yaml-editor"
          value={yaml}
          onChange={(e) => { setYaml(e.target.value); setDirty(true); }}
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
        />
      )}

      {hasPort && (
        <div className="hint" style={{ marginTop: 8 }}>
          ⚠️ <code>server.port</code> 变更需要重启应用后生效。
        </div>
      )}
    </div>
  );
}
