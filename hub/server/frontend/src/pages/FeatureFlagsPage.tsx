import {useCallback, useEffect, useMemo, useState} from 'react';
import {
  listOrgFlagSettings,
  listOrgToolSettings,
  listPlatformFlags,
  listPlatformTools,
  setOrgFlagEnabled,
  setOrgToolEnabled,
} from '../api';
import type {FeatureFlagDto, OrgFlagSettingDto, OrgToolSettingDto, PlatformToolDto} from '../types';

interface Props {
  /** 当前组织 id；为 null 时显示「先创建组织」空态 */
  orgId: number | null;
}

type TabKey = 'flags' | 'tools';

const TABS: {key: TabKey; label: string}[] = [
  {key: 'flags', label: '功能开关'},
  {key: 'tools', label: '工具'},
];

/**
 * 组织功能开关与工具启用：平台目录只读展示，组织侧仅可切换「是否启用」。
 * 未设置过的开关/工具默认启用；平台停用的条目不可切换（平台总开关优先）。
 */
export default function FeatureFlagsPage({orgId}: Props) {
  const [tab, setTab] = useState<TabKey>('flags');

  if (orgId == null) {
    return (
      <div className="page">
        <div className="empty-state">
          <h3>尚未选择组织</h3>
          <p>请先创建或选择一个组织，再配置功能开关与工具。</p>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>开关与工具</h2>
          <p className="page-desc">
            功能开关与工具目录由平台统一维护（只读）；这里决定本组织启用哪些。未设置过的条目默认启用。
          </p>
        </div>
      </div>

      <div className="page-tabs">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            className={tab === t.key ? 'tab-btn active' : 'tab-btn'}
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'flags' && <OrgFlagsTab orgId={orgId} />}
      {tab === 'tools' && <OrgToolsTab orgId={orgId} />}
    </div>
  );
}

// ============ 功能开关 tab ============

function OrgFlagsTab({orgId}: {orgId: number}) {
  const [flags, setFlags] = useState<FeatureFlagDto[] | null>(null);
  const [settings, setSettings] = useState<OrgFlagSettingDto[] | null>(null);
  const [error, setError] = useState('');
  const [busyId, setBusyId] = useState<number | null>(null);

  const reload = useCallback(async () => {
    try {
      const [platformFlags, orgSettings] = await Promise.all([listPlatformFlags(), listOrgFlagSettings(orgId)]);
      setFlags(platformFlags);
      setSettings(orgSettings);
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载功能开关目录失败');
    }
  }, [orgId]);

  useEffect(() => {
    setFlags(null);
    setSettings(null);
    void reload();
  }, [reload]);

  /** flagId → 该组织生效态；无行 = 默认启用 */
  const enabledById = useMemo(() => {
    const map = new Map<number, boolean>();
    for (const s of settings ?? []) map.set(s.flagId, s.enabled);
    return map;
  }, [settings]);

  const onToggle = async (f: FeatureFlagDto) => {
    if (!f.enabled) return;
    const current = enabledById.get(f.id) ?? true;
    setBusyId(f.id);
    try {
      await setOrgFlagEnabled(orgId, f.id, !current);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : '切换失败');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <>
      {error && <div className="form-error">{error}</div>}
      {flags == null && !error && <div className="empty-hint">加载中…</div>}

      {flags != null && (
        <div className="card">
          {flags.length === 0 ? (
            <div className="empty-state">
              <h3>平台功能开关目录为空</h3>
              <p>平台尚未配置任何功能开关，请联系平台管理员。</p>
            </div>
          ) : (
            <table className="data-table">
              <thead>
                <tr>
                  <th>标识</th>
                  <th>名称</th>
                  <th>描述</th>
                  <th>平台状态</th>
                  <th>本组织启用</th>
                </tr>
              </thead>
              <tbody>
                {flags.map((f) => {
                  const enabled = enabledById.get(f.id) ?? true;
                  return (
                    <tr key={f.id} className={f.enabled ? '' : 'menu-row-disabled'}>
                      <td>
                        <code className="muted menu-key">{f.flagKey}</code>
                      </td>
                      <td>{f.label}</td>
                      <td className="cell-ellipsis">{f.description || '—'}</td>
                      <td>{f.enabled ? <span className="badge">启用</span> : <span className="badge badge-archived">平台停用</span>}</td>
                      <td>
                        {f.enabled ? (
                          <button
                            type="button"
                            className={enabled ? 'menu-toggle on' : 'menu-toggle off'}
                            onClick={() => onToggle(f)}
                            disabled={busyId === f.id}
                            title={enabled ? '点击停用' : '点击启用'}
                          >
                            {enabled ? '启用' : '停用'}
                          </button>
                        ) : (
                          <span className="muted">—</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
      )}
    </>
  );
}

// ============ 工具 tab ============

function OrgToolsTab({orgId}: {orgId: number}) {
  const [tools, setTools] = useState<PlatformToolDto[] | null>(null);
  const [settings, setSettings] = useState<OrgToolSettingDto[] | null>(null);
  const [error, setError] = useState('');
  const [busyId, setBusyId] = useState<number | null>(null);

  const reload = useCallback(async () => {
    try {
      const [platformTools, orgSettings] = await Promise.all([listPlatformTools(), listOrgToolSettings(orgId)]);
      setTools(platformTools);
      setSettings(orgSettings);
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载工具目录失败');
    }
  }, [orgId]);

  useEffect(() => {
    setTools(null);
    setSettings(null);
    void reload();
  }, [reload]);

  /** toolId → 该组织生效态；无行 = 默认启用 */
  const enabledById = useMemo(() => {
    const map = new Map<number, boolean>();
    for (const s of settings ?? []) map.set(s.toolId, s.enabled);
    return map;
  }, [settings]);

  const onToggle = async (t: PlatformToolDto) => {
    if (!t.enabled) return;
    const current = enabledById.get(t.id) ?? true;
    setBusyId(t.id);
    try {
      await setOrgToolEnabled(orgId, t.id, !current);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : '切换失败');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <>
      {error && <div className="form-error">{error}</div>}
      {tools == null && !error && <div className="empty-hint">加载中…</div>}

      {tools != null && (
        <div className="card">
          {tools.length === 0 ? (
            <div className="empty-state">
              <h3>平台工具目录为空</h3>
              <p>工具清单由系统启动时自动同步，当前为空。</p>
            </div>
          ) : (
            <table className="data-table">
              <thead>
                <tr>
                  <th>工具</th>
                  <th>标识</th>
                  <th>分组</th>
                  <th>描述</th>
                  <th>平台状态</th>
                  <th>本组织启用</th>
                </tr>
              </thead>
              <tbody>
                {tools.map((t) => {
                  const enabled = enabledById.get(t.id) ?? true;
                  return (
                    <tr key={t.id} className={t.enabled ? '' : 'menu-row-disabled'}>
                      <td>{t.displayName}</td>
                      <td>
                        <code className="muted menu-key">{t.toolKey}</code>
                      </td>
                      <td>
                        <span className="badge">{t.toolGroup}</span>
                      </td>
                      <td className="cell-ellipsis">{t.description || '—'}</td>
                      <td>{t.enabled ? <span className="badge">启用</span> : <span className="badge badge-archived">平台停用</span>}</td>
                      <td>
                        {t.enabled ? (
                          <button
                            type="button"
                            className={enabled ? 'menu-toggle on' : 'menu-toggle off'}
                            onClick={() => onToggle(t)}
                            disabled={busyId === t.id}
                            title={enabled ? '点击停用' : '点击启用'}
                          >
                            {enabled ? '启用' : '停用'}
                          </button>
                        ) : (
                          <span className="muted">—</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
      )}
    </>
  );
}
