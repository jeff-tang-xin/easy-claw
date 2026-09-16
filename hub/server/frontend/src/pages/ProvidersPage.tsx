import {useCallback, useEffect, useState} from 'react';
import {createProvider, deleteProvider, listOrgOptions, listProviders, updateProvider} from '../api';
import {loadSession} from '../auth';
import Modal from '../components/Modal';
import type {OrgOptionDto, ProviderDto} from '../types';

const STATUS_LABELS: Record<string, string> = {
  active: '正常',
  disabled: '禁用',
};

const API_TYPE_LABELS: Record<string, string> = {
  openai: 'OpenAI 兼容',
  anthropic: 'Anthropic',
};

/**
 * 主流厂商模板：选中后仅预填 baseUrl / 模型清单 / 协议类型，三项均可继续手改；
 * 模型清单会随厂商发版过时，以厂商官方文档为准（模板只是起步默认值）。
 */
interface ProviderTemplate {
  key: string;
  label: string;
  apiType: string;
  baseUrl: string;
  models: string;
}

const PROVIDER_TEMPLATES: ProviderTemplate[] = [
  {key: 'custom', label: '自定义', apiType: 'openai', baseUrl: '', models: ''},
  {
    key: 'openai',
    label: 'OpenAI',
    apiType: 'openai',
    baseUrl: 'https://api.openai.com/v1',
    models: 'gpt-4o, gpt-4o-mini, o3-mini',
  },
  {
    key: 'anthropic',
    label: 'Anthropic',
    apiType: 'anthropic',
    baseUrl: 'https://api.anthropic.com',
    models: 'claude-sonnet-4-5, claude-opus-4-1, claude-haiku-4-5',
  },
  {
    key: 'deepseek',
    label: 'DeepSeek',
    apiType: 'openai',
    baseUrl: 'https://api.deepseek.com',
    models: 'deepseek-chat, deepseek-reasoner',
  },
  {
    key: 'zhipu',
    label: '智谱 GLM',
    apiType: 'openai',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    models: 'glm-4.6, glm-4.5-air',
  },
  {
    key: 'moonshot',
    label: 'Moonshot Kimi',
    apiType: 'openai',
    baseUrl: 'https://api.moonshot.cn/v1',
    models: 'kimi-k2-0905-preview, moonshot-v1-8k',
  },
  {
    key: 'qwen',
    label: '通义千问',
    apiType: 'openai',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    models: 'qwen3-max, qwen-plus',
  },
];

/**
 * Provider 管理：平台共享池（platformAdmin 维护）+ 组织自有（该组织 owner/admin 维护）。
 * 列表可见范围 = 平台池 + 当前用户所在组织的 provider（服务端强制过滤）。
 * 真实 API Key 仅在创建/更新时提交，服务端加密存储、永不回显，列表只展示脱敏 keyHint。
 */
export default function ProvidersPage() {
  const session = loadSession();
  const isPlatformAdmin = session?.user.platformAdmin ?? false;
  // 当前用户可管理（owner/admin）的组织 id 集合：决定行级按钮显隐与创建时可选归属
  const managedOrgs = (session?.orgs ?? []).filter((o) => o.role === 'owner' || o.role === 'admin');
  const managedOrgIds = new Set(managedOrgs.map((o) => o.id));
  const canManage = (p: ProviderDto) =>
    isPlatformAdmin || (p.orgId !== null && managedOrgIds.has(p.orgId));
  const canCreate = isPlatformAdmin || managedOrgs.length > 0;

  const [providers, setProviders] = useState<ProviderDto[] | null>(null);
  const [loadError, setLoadError] = useState('');
  const [pageError, setPageError] = useState('');
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<ProviderDto | null>(null);
  const [templateKey, setTemplateKey] = useState('custom');
  const [slug, setSlug] = useState('');
  const [name, setName] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [apiType, setApiType] = useState('openai');
  const [modelsText, setModelsText] = useState('');
  const [remark, setRemark] = useState('');
  // 归属：'' = 平台共享池（仅 platformAdmin 可选）；否则组织 id 字串
  const [orgChoice, setOrgChoice] = useState('');
  const [orgOptions, setOrgOptions] = useState<OrgOptionDto[]>([]);
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    try {
      setLoadError('');
      setProviders(await listProviders());
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : '加载失败');
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  // 创建弹窗打开时：platformAdmin 拉全量组织选项（可建到任意组织）；非 admin 用会话内 owner/admin 组织
  const loadOrgOptions = useCallback(async () => {
    if (!isPlatformAdmin) return;
    try {
      setOrgOptions(await listOrgOptions());
    } catch {
      setOrgOptions([]);
    }
  }, [isPlatformAdmin]);

  const applyTemplate = (key: string) => {
    setTemplateKey(key);
    const t = PROVIDER_TEMPLATES.find((x) => x.key === key);
    if (!t || t.key === 'custom') return;
    setBaseUrl(t.baseUrl);
    setApiType(t.apiType);
    setModelsText(t.models);
    // slug/name 为空时顺手带出厂商名，已填不覆盖
    if (!slug.trim()) setSlug(t.key);
    if (!name.trim()) setName(t.label);
  };

  const openCreate = () => {
    setEditing(null);
    setTemplateKey('custom');
    setSlug('');
    setName('');
    setBaseUrl('');
    setApiKey('');
    setApiType('openai');
    setModelsText('');
    setRemark('');
    // 非平台管理员默认归属第一个可管理组织；平台管理员默认平台共享池
    setOrgChoice(isPlatformAdmin ? '' : managedOrgs.length > 0 ? String(managedOrgs[0].id) : '');
    setFormError('');
    setFormOpen(true);
    void loadOrgOptions();
  };

  const openEdit = (p: ProviderDto) => {
    setEditing(p);
    setSlug(p.slug);
    setName(p.name);
    setBaseUrl(p.baseUrl);
    setApiKey('');
    setApiType(p.apiType || 'openai');
    setModelsText(p.models.join(', '));
    setRemark(p.remark ?? '');
    setFormError('');
    setFormOpen(true);
  };

  const submitForm = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    const models = modelsText
      .split(',')
      .map((m) => m.trim())
      .filter((m) => m.length > 0);
    setBusy(true);
    try {
      if (editing) {
        await updateProvider(editing.id, {
          name: name.trim(),
          baseUrl: baseUrl.trim(),
          models,
          apiType,
          remark: remark.trim() || null,
          // 留空表示不更换密钥，不提交该字段
          ...(apiKey.trim() ? {apiKey: apiKey.trim()} : {}),
        });
      } else {
        await createProvider({
          slug: slug.trim(),
          name: name.trim(),
          baseUrl: baseUrl.trim(),
          apiKey: apiKey.trim(),
          models,
          remark: remark.trim() || null,
          orgId: orgChoice === '' ? null : Number(orgChoice),
          apiType,
        });
      }
      setFormOpen(false);
      await reload();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : editing ? '保存失败' : '创建失败');
    } finally {
      setBusy(false);
    }
  };

  const remove = async (p: ProviderDto) => {
    if (!window.confirm(`确定删除 Provider ${p.slug}（${p.name}）？删除后不可恢复。`)) return;
    setPageError('');
    try {
      await deleteProvider(p.id);
      await reload();
    } catch (err) {
      // 被 AppKey 绑定时服务端返回 409，message 说明占用情况
      setPageError(err instanceof Error ? err.message : '删除失败');
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>Provider 管理</h2>
          <p className="page-desc">
            平台共享池（平台管理员维护，全平台可用）+ 组织自有（组织 owner/admin 维护，仅本组织可用）；
            真实 API Key 仅创建/更新时提交，服务端加密存储、永不回显（列表仅展示脱敏尾号）。
          </p>
        </div>
        <div className="page-head-right">
          {canCreate && (
            <button type="button" className="btn btn-primary" onClick={openCreate}>
              ＋ 添加 Provider
            </button>
          )}
        </div>
      </div>

      {pageError && <div className="form-error">{pageError}</div>}

      {loadError ? (
        <div className="empty-state">
          <h3>加载失败</h3>
          <p>{loadError}</p>
        </div>
      ) : providers === null ? (
        <div className="empty-hint">加载中…</div>
      ) : providers.length === 0 ? (
        <div className="empty-state">
          <h3>暂无 Provider</h3>
          <p>
            {canCreate
              ? '点击右上角「添加 Provider」接入第一个模型供应商。'
              : '可见范围内暂无 Provider（平台共享池由平台管理员维护，组织 provider 由组织 owner/admin 维护）。'}
          </p>
        </div>
      ) : (
        <div className="card">
          <table className="data-table">
            <thead>
              <tr>
                <th>Slug</th>
                <th>名称</th>
                <th>归属</th>
                <th>协议</th>
                <th>Base URL</th>
                <th>模型</th>
                <th>状态</th>
                <th>密钥</th>
                <th>备注</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {providers.map((p) => (
                <tr key={p.id}>
                  <td style={{fontFamily: "ui-monospace, 'Cascadia Code', Consolas, monospace"}}>{p.slug}</td>
                  <td>{p.name}</td>
                  <td>
                    {p.orgId === null ? (
                      <span className="badge role-admin">平台共享池</span>
                    ) : (
                      <span className="badge role-member">{p.orgName ?? `组织 #${p.orgId}`}</span>
                    )}
                  </td>
                  <td>
                    <span className="badge badge-archived">{API_TYPE_LABELS[p.apiType] ?? p.apiType}</span>
                  </td>
                  <td>
                    <div className="cell-ellipsis" title={p.baseUrl}>
                      {p.baseUrl}
                    </div>
                  </td>
                  <td>
                    {p.models.length === 0
                      ? '—'
                      : p.models.map((m) => (
                          <span key={m} className="badge badge-archived" style={{marginRight: 4}}>
                            {m}
                          </span>
                        ))}
                  </td>
                  <td>
                    <span className={`badge ${p.status === 'active' ? 'role-member' : 'badge-archived'}`}>
                      {STATUS_LABELS[p.status] ?? p.status}
                    </span>
                  </td>
                  <td style={{fontFamily: "ui-monospace, 'Cascadia Code', Consolas, monospace"}}>
                    {p.keyHint ?? '—'}
                  </td>
                  <td>
                    <div className="cell-ellipsis" title={p.remark ?? ''}>
                      {p.remark ?? '—'}
                    </div>
                  </td>
                  <td style={{whiteSpace: 'nowrap'}}>
                    {canManage(p) ? (
                      <>
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => openEdit(p)}>
                          编辑
                        </button>{' '}
                        <button type="button" className="btn btn-danger btn-sm" onClick={() => void remove(p)}>
                          删除
                        </button>
                      </>
                    ) : (
                      <span className="field-hint">只读</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {formOpen && (
        <Modal
          title={editing ? `编辑 Provider：${editing.slug}` : '添加 Provider'}
          subtitle="真实 API Key 仅提交一次，服务端加密存储、永不回显"
          onClose={() => setFormOpen(false)}
          width={520}
        >
          <form className="modal-form" onSubmit={(e) => void submitForm(e)}>
            {!editing && (
              <label>
                厂商模板<span className="field-hint">选中自动预填，可继续修改</span>
                <select value={templateKey} onChange={(e) => applyTemplate(e.target.value)}>
                  {PROVIDER_TEMPLATES.map((t) => (
                    <option key={t.key} value={t.key}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </label>
            )}
            {!editing && (
              <label>
                归属<span className="field-hint">创建后不可修改；组织 provider 仅本组织可用</span>
                <select value={orgChoice} onChange={(e) => setOrgChoice(e.target.value)}>
                  {isPlatformAdmin && <option value="">平台共享池</option>}
                  {isPlatformAdmin
                    ? orgOptions.map((o) => (
                        <option key={o.id} value={o.id}>
                          {o.name}（{o.slug}）
                        </option>
                      ))
                    : managedOrgs.map((o) => (
                        <option key={o.id} value={o.id}>
                          {o.name}（{o.slug}）
                        </option>
                      ))}
                </select>
              </label>
            )}
            <label>
              Slug<span className="field-hint">归属范围内唯一，创建后不可修改</span>
              <input
                value={slug}
                onChange={(e) => setSlug(e.target.value)}
                maxLength={64}
                autoFocus={!editing}
                disabled={editing !== null}
                required
              />
            </label>
            <label>
              名称
              <input value={name} onChange={(e) => setName(e.target.value)} maxLength={128} required />
            </label>
            <label>
              协议类型<span className="field-hint">Anthropic 为原生协议，其余多为 OpenAI 兼容</span>
              <select value={apiType} onChange={(e) => setApiType(e.target.value)}>
                <option value="openai">OpenAI 兼容</option>
                <option value="anthropic">Anthropic</option>
              </select>
            </label>
            <label>
              Base URL
              <input
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
                maxLength={256}
                placeholder="https://api.example.com/v1"
                required
              />
            </label>
            <label>
              API Key{editing && <span className="field-hint">留空表示不更换</span>}
              <input
                type="password"
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
                maxLength={256}
                autoComplete="new-password"
                required={editing === null}
              />
            </label>
            <label>
              模型<span className="field-hint">逗号分隔，如 gpt-4o, gpt-4o-mini</span>
              <input
                value={modelsText}
                onChange={(e) => setModelsText(e.target.value)}
                maxLength={1024}
                placeholder="gpt-4o, gpt-4o-mini"
                required
              />
            </label>
            <label>
              备注（可选）
              <input value={remark} onChange={(e) => setRemark(e.target.value)} maxLength={256} />
            </label>
            {formError && <div className="form-error">{formError}</div>}
            <div className="modal-actions">
              <button type="button" className="btn btn-ghost" onClick={() => setFormOpen(false)}>
                取消
              </button>
              <button type="submit" className="btn btn-primary" disabled={busy}>
                {busy ? '提交中…' : editing ? '保存' : '创建'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
