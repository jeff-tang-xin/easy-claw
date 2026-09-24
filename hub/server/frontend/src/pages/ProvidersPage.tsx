import {useCallback, useEffect, useState} from 'react';
import {
  addProviderCredits,
  adminListUsers,
  createProvider,
  createProviderGrant,
  deleteProvider,
  deleteProviderGrant,
  listOrgMembers,
  listOrgOptions,
  listProviderCredits,
  listProviderGrants,
  listProviders,
  updateGrantPlan,
  updateProvider,
} from '../api';
import {loadSession} from '../auth';
import Modal from '../components/Modal';
import type {
  MemberDto,
  OrgOptionDto,
  ProviderCreditDto,
  ProviderDto,
  ProviderGrantDto,
  UserDto,
} from '../types';

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

  // ============ 授权管理：按用户授权 + 可选每日次数上限/有效期；配置任意授权后仅清单内用户可用 ============
  const [grantProvider, setGrantProvider] = useState<ProviderDto | null>(null);
  const [grants, setGrants] = useState<ProviderGrantDto[] | null>(null);
  // 授权用户候选：组织 provider 用成员列表；平台池（仅 platformAdmin 可管理）用全量用户
  const [candidates, setCandidates] = useState<{id: number; label: string}[]>([]);
  const [grantUserId, setGrantUserId] = useState('');
  const [grantLimit, setGrantLimit] = useState('');
  const [grantExpires, setGrantExpires] = useState('');
  const [grantError, setGrantError] = useState('');
  const [grantBusy, setGrantBusy] = useState(false);

  const openGrants = async (p: ProviderDto) => {
    setGrantProvider(p);
    setGrants(null);
    setGrantUserId('');
    setGrantLimit('');
    setGrantExpires('');
    setGrantError('');
    try {
      const [g, c] = await Promise.all([
        listProviderGrants(p.id),
        p.orgId !== null
          ? listOrgMembers(p.orgId).then((ms: MemberDto[]) =>
              ms.map((m) => ({id: m.userId, label: m.displayName ? `${m.displayName}（${m.username}）` : m.username})),
            )
          : adminListUsers().then((us: UserDto[]) =>
              us.map((u) => ({id: u.id, label: u.displayName ? `${u.displayName}（${u.username}）` : u.username})),
            ),
      ]);
      setGrants(g);
      setCandidates(c);
    } catch (err) {
      setGrantError(err instanceof Error ? err.message : '加载授权失败');
      setGrants([]);
    }
  };

  const submitGrant = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!grantProvider) return;
    setGrantError('');
    setGrantBusy(true);
    try {
      await createProviderGrant(grantProvider.id, {
        userId: Number(grantUserId),
        // 留空 = 不限流；有效期留空 = 永久
        ...(grantLimit.trim() ? {dailyLimit: Number(grantLimit)} : {}),
        ...(grantExpires ? {expiresAt: new Date(grantExpires).toISOString()} : {}),
      });
      setGrants(await listProviderGrants(grantProvider.id));
      setGrantUserId('');
      setGrantLimit('');
      setGrantExpires('');
    } catch (err) {
      setGrantError(err instanceof Error ? err.message : '添加授权失败');
    } finally {
      setGrantBusy(false);
    }
  };

  const removeGrant = async (g: ProviderGrantDto) => {
    if (!grantProvider) return;
    setGrantError('');
    try {
      await deleteProviderGrant(grantProvider.id, g.id);
      setGrants(await listProviderGrants(grantProvider.id));
    } catch (err) {
      setGrantError(err instanceof Error ? err.message : '取消授权失败');
    }
  };

  // ============ 积分管理（V27）：周期发放计划 + 手动临时积分 + 流水；FIFO 按过期时间消耗 ============
  const [creditGrant, setCreditGrant] = useState<ProviderGrantDto | null>(null);
  const [creditRows, setCreditRows] = useState<ProviderCreditDto[] | null>(null);
  // 发放计划表单（空串 = 不发放该周期）
  const [planDaily, setPlanDaily] = useState('');
  const [planMonthly, setPlanMonthly] = useState('');
  const [planYearly, setPlanYearly] = useState('');
  // 临时积分表单
  const [tempCredits, setTempCredits] = useState('');
  const [tempExpires, setTempExpires] = useState('');
  const [creditError, setCreditError] = useState('');
  const [creditBusy, setCreditBusy] = useState(false);

  const openCredits = (g: ProviderGrantDto) => {
    setCreditGrant(g);
    setCreditRows(null);
    setPlanDaily(g.dailyCredits === null ? '' : String(g.dailyCredits));
    setPlanMonthly(g.monthlyCredits === null ? '' : String(g.monthlyCredits));
    setPlanYearly(g.yearlyCredits === null ? '' : String(g.yearlyCredits));
    setTempCredits('');
    setTempExpires('');
    setCreditError('');
    if (grantProvider) {
      listProviderCredits(grantProvider.id, g.id)
        .then(setCreditRows)
        .catch((err) => {
          setCreditError(err instanceof Error ? err.message : '加载积分流水失败');
          setCreditRows([]);
        });
    }
  };

  const reloadGrantsQuietly = async () => {
    if (!grantProvider) return;
    try {
      const fresh = await listProviderGrants(grantProvider.id);
      setGrants(fresh);
      // 同步刷新积分弹窗里的授权行（余额列随消耗变化）
      const current = creditGrant?.id;
      if (current != null) {
        const match = fresh.find((x) => x.id === current);
        if (match) setCreditGrant(match);
      }
    } catch {
      // 静默：主列表刷新失败不阻塞积分操作
    }
  };

  const submitPlan = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!grantProvider || !creditGrant) return;
    setCreditError('');
    setCreditBusy(true);
    try {
      await updateGrantPlan(grantProvider.id, creditGrant.id, {
        dailyCredits: planDaily.trim() ? Number(planDaily) : null,
        monthlyCredits: planMonthly.trim() ? Number(planMonthly) : null,
        yearlyCredits: planYearly.trim() ? Number(planYearly) : null,
      });
      await reloadGrantsQuietly();
    } catch (err) {
      setCreditError(err instanceof Error ? err.message : '保存发放计划失败');
    } finally {
      setCreditBusy(false);
    }
  };

  const submitTempCredits = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!grantProvider || !creditGrant) return;
    setCreditError('');
    setCreditBusy(true);
    try {
      await addProviderCredits(grantProvider.id, creditGrant.id, {
        credits: Number(tempCredits),
        expiresAt: new Date(tempExpires).toISOString(),
      });
      setTempCredits('');
      setTempExpires('');
      setCreditRows(await listProviderCredits(grantProvider.id, creditGrant.id));
      await reloadGrantsQuietly();
    } catch (err) {
      setCreditError(err instanceof Error ? err.message : '发放临时积分失败');
    } finally {
      setCreditBusy(false);
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
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => void openGrants(p)}>
                          授权
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

      {grantProvider && (
        <Modal
          title={`授权管理：${grantProvider.slug}`}
          subtitle="未配置任何授权 = 所有 AppKey 可用；配置后仅清单内用户可用，可另设每日次数上限与有效期"
          onClose={() => setGrantProvider(null)}
          width={640}
        >
          <div className="modal-form">
            {grants === null ? (
              <div className="empty-hint">加载中…</div>
            ) : grants.length === 0 ? (
              <div className="empty-hint">暂无授权记录（当前开放给所有 AppKey 使用）</div>
            ) : (
              <table className="data-table">
                <thead>
                  <tr>
                    <th>用户</th>
                    <th>每日上限</th>
                    <th>今日已用</th>
                    <th>积分余额</th>
                    <th>有效期至</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {grants.map((g) => (
                    <tr key={g.id}>
                      <td>{g.username ?? `用户 #${g.userId}`}</td>
                      <td>{g.dailyLimit ?? '不限'}</td>
                      <td>{g.usedToday ?? '—'}</td>
                      <td>
                        {g.remainingCredits === null ? (
                          <span className="field-hint">未启用</span>
                        ) : (
                          <span
                            className="badge role-member"
                            title="Σ 未过期面额 − 已消耗；按过期时间先后 FIFO 消耗"
                          >
                            ⚡ {g.remainingCredits}
                          </span>
                        )}
                      </td>
                      <td>
                        {g.expiresAt
                          ? new Date(g.expiresAt).toLocaleString('zh-CN', {hour12: false})
                          : '永久'}
                      </td>
                      <td style={{whiteSpace: 'nowrap'}}>
                        <button
                          type="button"
                          className="btn btn-ghost btn-sm"
                          onClick={() => openCredits(g)}
                        >
                          积分
                        </button>{' '}
                        <button
                          type="button"
                          className="btn btn-danger btn-sm"
                          onClick={() => void removeGrant(g)}
                        >
                          取消授权
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            <form onSubmit={(e) => void submitGrant(e)}>
              <label>
                授权用户
                <select value={grantUserId} onChange={(e) => setGrantUserId(e.target.value)} required>
                  <option value="" disabled>
                    选择用户…
                  </option>
                  {candidates.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.label}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                每日调用上限<span className="field-hint">留空 = 不限流；按请求次数计，成功也计数</span>
                <input
                  type="number"
                  min={1}
                  value={grantLimit}
                  onChange={(e) => setGrantLimit(e.target.value)}
                  placeholder="如 100"
                />
              </label>
              <label>
                有效期至<span className="field-hint">留空 = 永久；须为未来时间</span>
                <input
                  type="datetime-local"
                  value={grantExpires}
                  onChange={(e) => setGrantExpires(e.target.value)}
                />
              </label>
              {grantError && <div className="form-error">{grantError}</div>}
              <div className="modal-actions">
                <button type="button" className="btn btn-ghost" onClick={() => setGrantProvider(null)}>
                  关闭
                </button>
                <button type="submit" className="btn btn-primary" disabled={grantBusy || !grantUserId}>
                  {grantBusy ? '提交中…' : '添加授权'}
                </button>
              </div>
            </form>
          </div>
        </Modal>
      )}

      {creditGrant && grantProvider && (
        <Modal
          title={`积分管理：${creditGrant.username ?? `用户 #${creditGrant.userId}`}`}
          subtitle="按请求模型的目录比例扣积分（未登记模型 1 分/次）；按过期时间先后 FIFO 消耗，过期未耗尽部分作废"
          onClose={() => setCreditGrant(null)}
          width={640}
        >
          <div className="modal-form">
            <form onSubmit={(e) => void submitPlan(e)}>
              <label>
                每日发放<span className="field-hint">当天有效次日重发；留空 = 不发放（推荐 500）</span>
                <input
                  type="number"
                  min={0}
                  max={1000000}
                  value={planDaily}
                  onChange={(e) => setPlanDaily(e.target.value)}
                  placeholder="如 500"
                />
              </label>
              <label>
                每月发放<span className="field-hint">当月有效；留空 = 不发放</span>
                <input
                  type="number"
                  min={0}
                  max={1000000}
                  value={planMonthly}
                  onChange={(e) => setPlanMonthly(e.target.value)}
                  placeholder="如 10000"
                />
              </label>
              <label>
                每年发放<span className="field-hint">当年有效；留空 = 不发放</span>
                <input
                  type="number"
                  min={0}
                  max={1000000}
                  value={planYearly}
                  onChange={(e) => setPlanYearly(e.target.value)}
                  placeholder="如 100000"
                />
              </label>
              <div className="modal-actions">
                <button type="submit" className="btn btn-primary" disabled={creditBusy}>
                  {creditBusy ? '提交中…' : '保存发放计划'}
                </button>
              </div>
            </form>

            <hr style={{border: 'none', borderTop: '1px solid var(--border, #e0e0e0)', margin: '8px 0'}} />

            <form onSubmit={(e) => void submitTempCredits(e)}>
              <label>
                手动发放临时积分<span className="field-hint">与周期积分同池消耗；有效期必填</span>
                <input
                  type="number"
                  min={1}
                  max={1000000}
                  value={tempCredits}
                  onChange={(e) => setTempCredits(e.target.value)}
                  placeholder="面额，如 200"
                  required
                />
              </label>
              <label>
                有效期至<span className="field-hint">须为未来时间；典型 1 天 / 1 个月</span>
                <input
                  type="datetime-local"
                  value={tempExpires}
                  onChange={(e) => setTempExpires(e.target.value)}
                  required
                />
              </label>
              <div className="modal-actions">
                <button type="submit" className="btn btn-primary" disabled={creditBusy || !tempCredits}>
                  {creditBusy ? '提交中…' : '发放'}
                </button>
              </div>
            </form>

            <hr style={{border: 'none', borderTop: '1px solid var(--border, #e0e0e0)', margin: '8px 0'}} />

            <div>
              <div style={{fontWeight: 600, marginBottom: 6}}>积分流水</div>
              {creditRows === null ? (
                <div className="empty-hint">加载中…</div>
              ) : creditRows.length === 0 ? (
                <div className="empty-hint">暂无积分记录（当期首笔请求时自动发放周期积分）</div>
              ) : (
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>类型</th>
                      <th>面额</th>
                      <th>已耗</th>
                      <th>剩余</th>
                      <th>过期时间</th>
                    </tr>
                  </thead>
                  <tbody>
                    {creditRows.map((c) => {
                      const expired = new Date(c.expiresAt).getTime() < Date.now();
                      return (
                        <tr key={c.id}>
                          <td>
                            {c.periodType === 'temp'
                              ? '临时'
                              : c.periodType === 'daily'
                                ? '每日'
                                : c.periodType === 'monthly'
                                  ? '每月'
                                  : '每年'}
                            {c.periodKey && (
                              <span className="field-hint" style={{marginLeft: 4}}>
                                {c.periodKey}
                              </span>
                            )}
                          </td>
                          <td>{c.credits}</td>
                          <td>{c.consumed}</td>
                          <td style={expired ? {textDecoration: 'line-through', opacity: 0.6} : undefined}>
                            {c.remaining}
                            {expired && <span className="field-hint">（已过期）</span>}
                          </td>
                          <td>{new Date(c.expiresAt).toLocaleString('zh-CN', {hour12: false})}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              )}
            </div>

            {creditError && <div className="form-error">{creditError}</div>}
          </div>
        </Modal>
      )}
    </div>
  );
}
