import {useCallback, useEffect, useMemo, useState} from 'react';
import {
  createAppKey,
  listAppKeys,
  listProviders,
  revokeAppKey,
  updateAppKeyBindings,
  type AppKeyBindingInput,
} from '../api';
import Modal from '../components/Modal';
import type {AppKeyCreatedResponse, AppKeyDto, ProviderDto} from '../types';

const STATUS_LABELS: Record<string, string> = {
  active: '正常',
  revoked: '已吊销',
};

/** 绑定编辑行；modelName 为 '' 表示该 provider 的全部模型 */
interface BindingRow {
  providerId: number;
  modelName: string;
}

const formatTime = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : '—');

const toPayload = (rows: BindingRow[]): AppKeyBindingInput[] =>
  rows.map((r) => ({providerId: r.providerId, ...(r.modelName ? {modelName: r.modelName} : {})}));

/**
 * 绑定编辑器：provider 下拉（active 项 + 已被引用的禁用项）+ 模型下拉（全部模型 / 该 provider 的模型）。
 * 同 provider+model 的重复行由调用方在提交时拦截。
 */
function BindingEditor({
  rows,
  setRows,
  providers,
}: {
  rows: BindingRow[];
  setRows: (rows: BindingRow[]) => void;
  providers: ProviderDto[];
}) {
  const providerOptions = useMemo(() => {
    const referenced = new Set(rows.map((r) => r.providerId));
    const active = providers.filter((p) => p.status === 'active');
    const extra = providers.filter((p) => p.status !== 'active' && referenced.has(p.id));
    return [...active, ...extra];
  }, [rows, providers]);

  const addRow = () => {
    if (providerOptions.length === 0) return;
    setRows([...rows, {providerId: providerOptions[0].id, modelName: ''}]);
  };

  const updateRow = (index: number, patch: Partial<BindingRow>) => {
    setRows(rows.map((r, i) => (i === index ? {...r, ...patch} : r)));
  };

  const removeRow = (index: number) => {
    setRows(rows.filter((_, i) => i !== index));
  };

  if (providerOptions.length === 0) {
    return <div className="field-hint">暂无可用的 Provider，请先在「Provider」页添加。</div>;
  }

  return (
    <div>
      {rows.map((row, i) => {
        const provider = providers.find((p) => p.id === row.providerId);
        const models = provider?.models ?? [];
        // 已有绑定可能引用 provider 后来移除的模型，保留该选项避免数据被静默改写
        const modelOptions =
          row.modelName === '' || models.includes(row.modelName) ? models : [...models, row.modelName];
        return (
          <div key={i} style={{display: 'flex', gap: 8, marginBottom: 8, alignItems: 'center'}}>
            <select
              value={row.providerId}
              onChange={(e) => updateRow(i, {providerId: Number(e.target.value), modelName: ''})}
              style={{flex: 1}}
            >
              {providerOptions.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}（{p.slug}）{p.status !== 'active' ? '［已禁用］' : ''}
                </option>
              ))}
            </select>
            <select
              value={row.modelName}
              onChange={(e) => updateRow(i, {modelName: e.target.value})}
              style={{flex: 1}}
            >
              <option value="">全部模型</option>
              {modelOptions.map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
            </select>
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => removeRow(i)}
              disabled={rows.length <= 1}
            >
              删除
            </button>
          </div>
        );
      })}
      <button type="button" className="btn btn-ghost btn-sm" onClick={addRow}>
        ＋ 添加绑定
      </button>
    </div>
  );
}

/** 校验绑定行：至少 1 行、provider 有效、无重复 provider+model 组合 */
const validateRows = (rows: BindingRow[]): string => {
  if (rows.length === 0) return '请至少添加一条绑定';
  if (rows.some((r) => !r.providerId)) return '存在未选择 Provider 的绑定行';
  const keys = rows.map((r) => `${r.providerId}|${r.modelName}`);
  if (new Set(keys).size !== keys.length) return '存在重复的绑定行（同一 Provider 的同一模型）';
  return '';
};

/**
 * 组织级 AppKey 管理（owner/admin，服务端强制校验）。
 * plainKey 仅在创建响应中返回一次，创建成功视图必须醒目展示并提示保存。
 */
export default function AppKeysPage({orgId}: {orgId: number | null}) {
  const [appKeys, setAppKeys] = useState<AppKeyDto[] | null>(null);
  const [providers, setProviders] = useState<ProviderDto[]>([]);
  const [loadError, setLoadError] = useState('');
  const [pageError, setPageError] = useState('');
  // 创建弹窗：form 视图 / 成功视图（created 非空时）
  const [createOpen, setCreateOpen] = useState(false);
  const [created, setCreated] = useState<AppKeyCreatedResponse | null>(null);
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState('');
  // 编辑绑定弹窗
  const [editTarget, setEditTarget] = useState<AppKeyDto | null>(null);
  const [name, setName] = useState('');
  const [rows, setRows] = useState<BindingRow[]>([]);
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    if (orgId === null) return;
    try {
      setLoadError('');
      setAppKeys(await listAppKeys(orgId));
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : '加载失败');
    }
  }, [orgId]);

  useEffect(() => {
    setAppKeys(null);
    void reload();
  }, [reload]);

  useEffect(() => {
    // Provider 列表登录可读；加载失败不阻断页面，仅影响绑定编辑器
    listProviders()
      .then(setProviders)
      .catch(() => setProviders([]));
  }, []);

  const openCreate = () => {
    setName('');
    setRows([]);
    setFormError('');
    setCreated(null);
    setCopied(false);
    setCopyError('');
    setCreateOpen(true);
  };

  const openEditBindings = (k: AppKeyDto) => {
    setEditTarget(k);
    setRows(k.bindings.map((b) => ({providerId: b.providerId, modelName: b.modelName})));
    setFormError('');
  };

  const submitCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (orgId === null) return;
    const err = validateRows(rows);
    setFormError(err);
    if (err) return;
    setBusy(true);
    try {
      const result = await createAppKey(orgId, name.trim(), toPayload(rows));
      // 成功后切到「创建成功」视图展示 plainKey（仅此一次，不许跳过）
      setCreated(result);
    } catch (err2) {
      setFormError(err2 instanceof Error ? err2.message : '创建失败');
    } finally {
      setBusy(false);
    }
  };

  const closeCreate = async () => {
    setCreateOpen(false);
    setCreated(null);
    await reload();
  };

  const submitEditBindings = async (e: React.FormEvent) => {
    e.preventDefault();
    if (orgId === null || editTarget === null) return;
    const err = validateRows(rows);
    setFormError(err);
    if (err) return;
    setBusy(true);
    try {
      await updateAppKeyBindings(orgId, editTarget.id, toPayload(rows));
      setEditTarget(null);
      await reload();
    } catch (err2) {
      setFormError(err2 instanceof Error ? err2.message : '保存失败');
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (k: AppKeyDto) => {
    if (orgId === null) return;
    if (!window.confirm(`确定吊销 AppKey「${k.name}」（${k.keyPrefix}…）？吊销后立即失效且不可恢复。`)) return;
    setPageError('');
    try {
      await revokeAppKey(orgId, k.id);
      await reload();
    } catch (err) {
      setPageError(err instanceof Error ? err.message : '吊销失败');
    }
  };

  const copyPlainKey = async (plainKey: string) => {
    setCopyError('');
    try {
      await navigator.clipboard.writeText(plainKey);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopyError('复制失败，请手动选中密钥复制');
    }
  };

  if (orgId === null) {
    return (
      <div className="page">
        <div className="page-head">
          <div>
            <h2>AppKey 管理</h2>
          </div>
        </div>
        <div className="empty-state">
          <h3>请先加入组织</h3>
          <p>AppKey 归属于组织，加入组织后即可管理。</p>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>AppKey 管理</h2>
          <p className="page-desc">组织级访问密钥；完整密钥仅在创建时显示一次，请立即保存。</p>
        </div>
        <div className="page-head-right">
          <button type="button" className="btn btn-primary" onClick={openCreate}>
            ＋ 创建 AppKey
          </button>
        </div>
      </div>

      {pageError && <div className="form-error">{pageError}</div>}

      {loadError ? (
        <div className="empty-state">
          <h3>加载失败</h3>
          <p>{loadError}</p>
        </div>
      ) : appKeys === null ? (
        <div className="empty-hint">加载中…</div>
      ) : appKeys.length === 0 ? (
        <div className="empty-state">
          <h3>暂无 AppKey</h3>
          <p>点击右上角「创建 AppKey」生成第一个访问密钥。</p>
        </div>
      ) : (
        <div className="card">
          <table className="data-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>密钥前缀</th>
                <th>状态</th>
                <th>绑定</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {appKeys.map((k) => (
                <tr key={k.id}>
                  <td>{k.name}</td>
                  <td style={{fontFamily: "ui-monospace, 'Cascadia Code', Consolas, monospace"}}>
                    {k.keyPrefix}…
                  </td>
                  <td>
                    <span
                      className={`badge ${k.status === 'active' ? 'role-member' : 'badge-archived'}`}
                      title={k.status === 'revoked' && k.revokedAt ? `吊销于 ${formatTime(k.revokedAt)}` : undefined}
                    >
                      {STATUS_LABELS[k.status] ?? k.status}
                    </span>
                  </td>
                  <td>
                    {k.bindings.length === 0
                      ? '—'
                      : k.bindings.map((b, i) => (
                          <span
                            key={i}
                            className="badge badge-archived"
                            style={{marginRight: 4}}
                            title={b.providerName ?? undefined}
                          >
                            {b.providerSlug ?? `#${b.providerId}`}:{b.modelName || '全部模型'}
                          </span>
                        ))}
                  </td>
                  <td>{formatTime(k.createdAt)}</td>
                  <td style={{whiteSpace: 'nowrap'}}>
                    {k.status === 'active' ? (
                      <>
                        <button
                          type="button"
                          className="btn btn-ghost btn-sm"
                          onClick={() => openEditBindings(k)}
                        >
                          编辑绑定
                        </button>{' '}
                        <button
                          type="button"
                          className="btn btn-danger btn-sm"
                          onClick={() => void revoke(k)}
                        >
                          吊销
                        </button>
                      </>
                    ) : (
                      '—'
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {createOpen && (
        <Modal
          title={created ? '创建成功' : '创建 AppKey'}
          subtitle={created ? undefined : '密钥创建后仅完整显示一次'}
          onClose={() => void closeCreate()}
          width={560}
        >
          {created ? (
            <div>
              <p>
                AppKey「{created.appKey.name}」已创建。请立即复制并妥善保存完整密钥：
              </p>
              <div
                style={{
                  fontFamily: "ui-monospace, 'Cascadia Code', Consolas, monospace",
                  wordBreak: 'break-all',
                  padding: '10px 12px',
                  background: 'var(--bg-input)',
                  border: '1px solid var(--border)',
                  borderRadius: 8,
                  margin: '12px 0',
                }}
              >
                {created.plainKey}
              </div>
              <div className="form-error">此密钥仅显示这一次，请立即保存；关闭后将无法再次查看。</div>
              {copyError && <div className="form-error">{copyError}</div>}
              <div className="modal-actions">
                <button
                  type="button"
                  className="btn btn-ghost"
                  onClick={() => void copyPlainKey(created.plainKey)}
                >
                  {copied ? '已复制 ✓' : '复制密钥'}
                </button>
                <button type="button" className="btn btn-primary" onClick={() => void closeCreate()}>
                  完成
                </button>
              </div>
            </div>
          ) : (
            <form className="modal-form" onSubmit={(e) => void submitCreate(e)}>
              <label>
                名称
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  maxLength={64}
                  placeholder="例如：生产环境后端服务"
                  autoFocus
                  required
                />
              </label>
              <label>
                绑定<span className="field-hint">限定该密钥可用的 Provider 与模型</span>
                <BindingEditor rows={rows} setRows={setRows} providers={providers} />
              </label>
              {formError && <div className="form-error">{formError}</div>}
              <div className="modal-actions">
                <button type="button" className="btn btn-ghost" onClick={() => setCreateOpen(false)}>
                  取消
                </button>
                <button type="submit" className="btn btn-primary" disabled={busy}>
                  {busy ? '创建中…' : '创建'}
                </button>
              </div>
            </form>
          )}
        </Modal>
      )}

      {editTarget && (
        <Modal
          title={`编辑绑定：${editTarget.name}`}
          subtitle={`密钥前缀 ${editTarget.keyPrefix}…`}
          onClose={() => setEditTarget(null)}
          width={560}
        >
          <form className="modal-form" onSubmit={(e) => void submitEditBindings(e)}>
            <label>
              绑定<span className="field-hint">限定该密钥可用的 Provider 与模型</span>
              <BindingEditor rows={rows} setRows={setRows} providers={providers} />
            </label>
            {formError && <div className="form-error">{formError}</div>}
            <div className="modal-actions">
              <button type="button" className="btn btn-ghost" onClick={() => setEditTarget(null)}>
                取消
              </button>
              <button type="submit" className="btn btn-primary" disabled={busy}>
                {busy ? '保存中…' : '保存'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
