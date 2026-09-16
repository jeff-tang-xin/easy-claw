import {useEffect, useState, type CSSProperties} from 'react';
import {getJson, putJson} from '../api';

interface MemorySettings {
  contextWindowTokens: number;
  flushMode: string;
  flushMinGapMinutes: number;
  flushAsyncEnabled: boolean;
  flushWindowMessages: number;
  flushBackgroundMessages: number;
  subagentFlushEnabled: boolean;
  memoryMdMaxKb: number;
  dailyRetentionDays: number;
  updatedAt?: string;
}

/**
 * 记忆设置卡片：读写 GET/PUT /api/memory/settings。
 * 存于系统库 memory_settings 表（与下方用户级 yml 是两套配置）；
 * 保存后后端自动重建全部 Workspace Agent，即时生效。
 */
function MemorySettingsCard() {
  const [settings, setSettings] = useState<MemorySettings | null>(null);
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  const load = async () => {
    setError('');
    try {
      setSettings(await getJson<MemorySettings>('/api/memory/settings'));
      setDirty(false);
    } catch (e) {
      setError(String(e));
    }
  };

  useEffect(() => { load(); }, []);

  const patch = (p: Partial<MemorySettings>) => {
    if (settings) {
      setSettings({ ...settings, ...p });
      setDirty(true);
    }
  };

  const save = async () => {
    if (!settings) return;
    setSaving(true);
    setError('');
    try {
      setSettings(await putJson<MemorySettings>('/api/memory/settings', {
        contextWindowTokens: settings.contextWindowTokens,
        flushMode: settings.flushMode,
        flushMinGapMinutes: settings.flushMinGapMinutes,
        flushAsyncEnabled: settings.flushAsyncEnabled,
        flushWindowMessages: settings.flushWindowMessages,
        flushBackgroundMessages: settings.flushBackgroundMessages,
        subagentFlushEnabled: settings.subagentFlushEnabled,
        memoryMdMaxKb: settings.memoryMdMaxKb,
        dailyRetentionDays: settings.dailyRetentionDays,
      }));
      setDirty(false);
      setToast('✅ 已保存并热生效（自动重建 Agent）');
      setTimeout(() => setToast(''), 2500);
    } catch (e) {
      setError('保存失败: ' + e);
    } finally {
      setSaving(false);
    }
  };

  const num = (v: string) => Math.max(1, parseInt(v || '1', 10) || 1);
  const fieldStyle: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 4, minWidth: 170 };

  return (
    <div className="card" style={{ padding: 16, marginBottom: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8, marginBottom: 12 }}>
        <div>
          <b>🧠 记忆设置</b>
          <span className="hint" style={{ marginLeft: 8 }}>存于系统库（非下方 yml），保存即热生效</span>
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

      {settings && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16 }}>
          <label style={fieldStyle}>
            <span>上下文压缩窗（tokens）</span>
            <input type="number" min={1024} step={1024} value={settings.contextWindowTokens}
                   onChange={(e) => patch({ contextWindowTokens: num(e.target.value) })} />
            <span className="hint">顶到即把前半截压缩为摘要，默认 48000</span>
          </label>

          <label style={fieldStyle}>
            <span>记忆提取策略</span>
            <select value={settings.flushMode} onChange={(e) => patch({ flushMode: e.target.value })}>
              <option value="throttled">throttled（按间隔节流）</option>
              <option value="always">always（每回合提取）</option>
              <option value="never">never（关闭自动提取）</option>
            </select>
            <span className="hint">never 时仅保留手动 memory_save</span>
          </label>

          <label style={fieldStyle}>
            <span>提取最小间隔（分钟）</span>
            <input type="number" min={1} value={settings.flushMinGapMinutes}
                   onChange={(e) => patch({ flushMinGapMinutes: num(e.target.value) })} />
            <span className="hint">仅 throttled 模式生效，默认 30</span>
          </label>

          <label style={fieldStyle}>
            <span>提取执行方式</span>
            <select value={settings.flushAsyncEnabled ? 'async' : 'sync'}
                    onChange={(e) => patch({ flushAsyncEnabled: e.target.value === 'async' })}>
              <option value="async">异步（推荐，不拖慢回合收尾）</option>
              <option value="sync">同步（等提取完成再结束回合）</option>
            </select>
            <span className="hint">同步档仅用于对照排查提取本身的问题</span>
          </label>

          <label style={fieldStyle}>
            <span>单次提取窗口（条）</span>
            <input type="number" min={1} max={50} value={settings.flushWindowMessages}
                   onChange={(e) => patch({ flushWindowMessages: num(e.target.value) })} />
            <span className="hint">每回合最多提取多少条新消息，超出下轮继续，默认 10</span>
          </label>

          <label style={fieldStyle}>
            <span>窗口前背景（条）</span>
            <input type="number" min={0} max={20} value={settings.flushBackgroundMessages}
                   onChange={(e) => patch({ flushBackgroundMessages: Math.max(0, parseInt(e.target.value || '0', 10) || 0) })} />
            <span className="hint">让提取模型看懂窗口语境，默认 5</span>
          </label>

          <label style={fieldStyle}>
            <span>MEMORY.md 体积上限（KB）</span>
            <input type="number" min={1} value={settings.memoryMdMaxKb}
                   onChange={(e) => patch({ memoryMdMaxKb: num(e.target.value) })} />
            <span className="hint">超限打 warn 提醒精简，默认 64</span>
          </label>

          <label style={fieldStyle}>
            <span>每日流水保留（天）</span>
            <input type="number" min={1} value={settings.dailyRetentionDays}
                   onChange={(e) => patch({ dailyRetentionDays: num(e.target.value) })} />
            <span className="hint">memory/YYYY-MM-DD.md 保留天数，默认 90</span>
          </label>

          <label style={{ ...fieldStyle, justifyContent: 'center' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <input type="checkbox" checked={settings.subagentFlushEnabled}
                     onChange={(e) => patch({ subagentFlushEnabled: e.target.checked })} />
              子 Agent 参与记忆提取
            </span>
            <span className="hint">默认关闭：子的沉淀经黑板归口主 Agent 统一写记忆</span>
          </label>
        </div>
      )}
    </div>
  );
}

export default function SettingsPage() {
  return (
    <div className="page settings-page">
      <div>
        <h1 className="page-title">⚙️ 设置</h1>
        <div className="hint">
          运行配置请手动编辑 <code>~/.easyClaw/application.yml</code>，修改后重启应用生效
        </div>
      </div>

      <MemorySettingsCard />
    </div>
  );
}
