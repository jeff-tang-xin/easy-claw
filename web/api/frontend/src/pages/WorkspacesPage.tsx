import {useEffect, useState} from 'react';
import {useParams, useNavigate} from 'react-router-dom';
import {
  bindCloudWorkspace, del, getCloudProjects, getJson, postJson, putJson, unbindCloudWorkspace,
  type CloudProject,
} from '../api';
import Modal from '../components/Modal';
import {useCloudConfig} from '../cloudConfig';

interface WorkspaceSummary {
  workspaceId: string;
  name: string;
  description: string;
  path: string;
  status: string;
  /** 工作区形态分类：single / team / schedule（存量工作区后端兜底为 single） */
  type?: string;
  /** 归属的 hub 项目（cloud 模式；本地模式恒 null） */
  projectId?: number | null;
  /** 绑定的数据库资源 serverKey（空 = 无数据库能力） */
  dbServerKey?: string | null;
  /** 绑定的运维服务器 serverKey（空 = 无远程执行能力） */
  opsServerKey?: string | null;
  createdAt: string;
}

interface PermRule { id: number; toolName: string; createdAt: string; }
interface ToolDef { name: string; displayName: string; requiresConfirm: boolean; }
interface ScenarioOption { id: number; name: string; displayName: string; icon?: string; active: boolean; mode?: string; }

/** 工作区可绑定的平台资源（对应后端 WorkspaceResourceService.ResourceOption）。
 *  label 只有业务名——主机、端口、账号不进前端。 */
interface ResourceOption { key: string; label: string; autoConnectable: boolean; }
interface ResourceOptions { databases: ResourceOption[]; servers: ResourceOption[]; }

/** 工作区形态分类（与场景 mode 同值域）到中文标签/图标的映射，顺序即侧边栏顺序。
 * 运维（ops）是系统内置唯一固定 workspace，不在此列表（不展示、不可创建），
 * 运维入口由 cloud 菜单下发（/ops，前端用固定 id 访问） */
const WS_TYPES = [
  { type: 'single', label: 'SOLO', icon: '👤', hint: '单个主智能体独立完成任务' },
] as const;

type WsType = typeof WS_TYPES[number]['type'];

/** 内置「通用编程」场景标识，与后端 SystemDataSeeder / WorkspaceController 保持一致 */
const DEFAULT_SCENARIO = 'general-coding';

export default function WorkspacesPage() {
  // 路由段决定当前分类：/workspaces/single | team | schedule；非法值回退 single
  const { wsType } = useParams<{ wsType: string }>();
  const currentType: WsType =
    (WS_TYPES.some((t) => t.type === wsType) ? wsType : 'single') as WsType;
  const typeMeta = WS_TYPES.find((t) => t.type === currentType)!;

  const [items, setItems] = useState<WorkspaceSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState('');
  const [path, setPath] = useState('');
  const [description, setDescription] = useState('');
  const [scenarioName, setScenarioName] = useState('');
  const [scenarios, setScenarios] = useState<ScenarioOption[]>([]);
  // cloud 模式：项目归属（创建必选；编辑可改绑）。projects 在进入 cloud 模式后拉取一次
  const cloudMode = useCloudConfig().cloudMode;
  const [projects, setProjects] = useState<CloudProject[]>([]);
  const [projectId, setProjectId] = useState<number | ''>('');
  /** 可绑定资源候选：按「当前表单选的归属项目」拉取，切换项目时重新加载 */
  const [resourceOptions, setResourceOptions] = useState<ResourceOptions | null>(null);
  const [dbKey, setDbKey] = useState('');
  const [opsKey, setOpsKey] = useState('');
  const [editDbKey, setEditDbKey] = useState('');
  const [editOpsKey, setEditOpsKey] = useState('');
  const navigate = useNavigate();

  /** 当前类型可选的场景：仅取已启用且 mode 与工作区类型一致的场景 */
  const scenariosForType = scenarios.filter((s) => (s.mode || 'single') === currentType);

  /** 当前分类下的工作区：存量数据 type 为 null 时按 single 兜底 */
  const visibleItems = items.filter((w) => (w.type || 'single') === currentType);

  // 白名单管理弹窗状态
  const [permWsId, setPermWsId] = useState<string | null>(null);
  const [permRules, setPermRules] = useState<PermRule[]>([]);
  const [permTools, setPermTools] = useState<ToolDef[]>([]);

  // 编辑弹窗状态（仅名称/描述可改，路径不可变以免沙箱根目录漂移）
  const [editing, setEditing] = useState<WorkspaceSummary | null>(null);
  const [editName, setEditName] = useState('');
  const [editDesc, setEditDesc] = useState('');
  const [editScenario, setEditScenario] = useState(DEFAULT_SCENARIO);
  // 编辑弹窗的项目归属（'' = 未绑定；仅 cloud 模式展示）
  const [editProjectId, setEditProjectId] = useState<number | ''>('');
  const [saving, setSaving] = useState(false);

  // 删除确认弹窗状态
  const [deleting, setDeleting] = useState<WorkspaceSummary | null>(null);
  const [removing, setRemoving] = useState(false);
  // 后端运行环境是否有图形界面：决定「📁 浏览」能否弹出系统目录选择器。
  // 探测失败按「可用」处理（不打扰正常用户）；真正的失败会在点击后以错误提示呈现。
  const [guiAvailable, setGuiAvailable] = useState(true);

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
    // 工作区列表全量拉取一次，前端按路由类型过滤（数据量小，避免三个分类各发一次请求）
    load();
    // GUI 能力探测：后端跑在无桌面会话的环境时 JFileChooser 弹不出来，
    // 按钮不禁用就会表现为「点了没反应」，这里提前降级为手动输入路径。
    getJson<boolean>('/api/system/has-gui')
      .then((ok) => setGuiAvailable(ok !== false))
      .catch(() => setGuiAvailable(true));
    // 场景列表只在挂载时拉一次：新建/编辑弹窗都要用，且变动频率极低
    getJson<ScenarioOption[]>('/api/scenarios')
      .then((list) => {
        const active = list.filter((s) => s.active);
        setScenarios(active);
        setScenarioName(defaultScenarioOf(active, currentType));
      })
      .catch(() => setScenarios([]));
    // 切换分类时把新建弹窗的默认场景重置为该类型的首个场景
  }, []);

  // 路由类型变化（侧边栏切分类）时同步新建默认场景并收起弹窗
  useEffect(() => {
    setShowCreate(false);
    setScenarioName(defaultScenarioOf(scenarios, currentType));
  }, [currentType]);

  // 进入 cloud 模式后拉取 hub 组织项目清单（创建/编辑弹窗的项目下拉数据源）
  useEffect(() => {
    if (!cloudMode) {
      setProjects([]);
      setProjectId('');
      return;
    }
    getCloudProjects()
      .then(setProjects)
      .catch(() => setProjects([]));
  }, [cloudMode]);

  /**
   * 可绑定资源候选：跟着「表单当前选的归属项目」走。
   * <p>切项目就重新拉——候选清单必须限定在本项目内，否则人在这个项目的工作区里会看到、
   * 甚至选到别项目的机器与库（后端写入时还会再校验一次，过滤是体验，校验才是边界）。
   * <p>未选项目时只请求「不限定项目」的那部分资源；本地模式通常两者皆空，UI 据此收起整块。
   */
  const activeProjectId = editing ? editProjectId : projectId;
  useEffect(() => {
    const url = activeProjectId === ''
      ? '/api/workspaces/resource-options'
      : `/api/workspaces/resource-options?projectId=${activeProjectId}`;
    getJson<ResourceOptions>(url)
      .then(setResourceOptions)
      .catch(() => setResourceOptions(null));
  }, [activeProjectId]);

  /**
   * 资源绑定表单块（创建 / 编辑共用）：两个可空单选下拉。
   * <p>候选由后端按归属项目过滤，label 只有业务名——主机、端口、账号不进浏览器。
   * <p>已绑定的资源可能因授权撤销而不在候选里，这里<b>保留原值并标注</b>：否则下拉会把它
   * 静默显示成「未绑定」，人以为绑错了，实际是授权没了——那种表现最难排查。
   */
  const resourceFields = (
    db: string, onDb: (v: string) => void,
    ops: string, onOps: (v: string) => void,
  ) => {
    if (!cloudMode) {
      return null;   // 本地模式没有平台资源来源，整块不渲染
    }
    const dbs = resourceOptions?.databases ?? [];
    const svrs = resourceOptions?.servers ?? [];
    if (dbs.length === 0 && svrs.length === 0) {
      return (
        <div className="field">
          <label>资源绑定（数据库 / 服务器）</label>
          <div className="hint">
            本项目下暂无可绑定的数据库或服务器。需要 AI 具备查库 / 远程执行能力，
            请先在平台把资源授权到该项目。
          </div>
        </div>
      );
    }
    return (
      <>
        <div className="field">
          <label>绑定数据库（可选）</label>
          <select value={db} onChange={(e) => onDb(e.target.value)}>
            <option value="">（不绑定 — 该工作区没有查库能力）</option>
            {dbs.map((o) => (
              <option key={o.key} value={o.key}>
                {o.label}{o.autoConnectable ? '' : '（需人工先连）'}
              </option>
            ))}
            {db && !dbs.some((o) => o.key === db) && (
              <option value={db}>{db}（当前绑定，已不在本项目清单内）</option>
            )}
          </select>
          <div className="hint">
            绑定后本工作区的智能体获得 db_query / db_schema / db_status，目标锁定为该库；
            模型看不到也无法指定连接地址。不绑定则这些工具根本不出现。
          </div>
        </div>
        <div className="field">
          <label>绑定服务器（可选）</label>
          <select value={ops} onChange={(e) => onOps(e.target.value)}>
            <option value="">（不绑定 — 该工作区没有远程执行能力）</option>
            {svrs.map((o) => (
              <option key={o.key} value={o.key}>
                {o.label}{o.autoConnectable ? '' : '（需人工先连）'}
              </option>
            ))}
            {ops && !svrs.some((o) => o.key === ops) && (
              <option value={ops}>{ops}（当前绑定，已不在本项目清单内）</option>
            )}
          </select>
          <div className="hint">
            每条命令仍逐条弹确认（不可被白名单或「永久允许」跳过）；
            当你当前终端连的是另一台机器时会拒绝执行，不会悄悄切换目标。
          </div>
        </div>
      </>
    );
  };

  /** 该类型的默认选中场景：优先内置「通用编程」（仅 single 类型内置），否则取同类型首个 */
  function defaultScenarioOf(list: ScenarioOption[], type: WsType): string {
    const sameType = list.filter((s) => (s.mode || 'single') === type);
    if (type === 'single' && sameType.some((s) => s.name === DEFAULT_SCENARIO)) {
      return DEFAULT_SCENARIO;
    }
    return sameType[0]?.name || '';
  }

  /** projectId → 项目名（拉取失败/未知时回退为 #id） */
  function projectName(id: number | null | undefined): string {
    if (id == null) return '';
    return projects.find((p) => p.id === id)?.name ?? `#${id}`;
  }

  const create = async () => {
    try {
      await postJson('/api/workspaces', {
        name,
        description,
        path,
        scenarioName,
        type: currentType,
        // cloud 模式必填（后端强制校验）；本地模式忽略
        projectId: cloudMode ? projectId : undefined,
        // 资源绑定非必须：空串 = 明确不绑定（后端据此不装相应能力）
        dbServerKey: dbKey,
        opsServerKey: opsKey,
      });
      setName('');
      setPath('');
      setDescription('');
      setScenarioName(defaultScenarioOf(scenarios, currentType));
      setProjectId('');
      setDbKey('');
      setOpsKey('');
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
    setEditProjectId(ws.projectId ?? '');
    setEditDbKey(ws.dbServerKey ?? '');
    setEditOpsKey(ws.opsServerKey ?? '');
    // 回显当前绑定：查不到（未绑定/接口异常）时落到该类型默认场景，不阻塞编辑
    setEditScenario(defaultScenarioOf(scenarios, currentType));
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
        // 总是提交：空串是「选回不绑定」的显式表达（后端 null 才表示不改，那是给老客户端留的）
        dbServerKey: editDbKey,
        opsServerKey: editOpsKey,
      });
      // cloud 模式：项目归属变更时同步 hub 绑定（'' 表示解除绑定）
      if (cloudMode) {
        const newId = editProjectId === '' ? null : editProjectId;
        if (newId !== (editing.projectId ?? null)) {
          if (newId === null) {
            await unbindCloudWorkspace(editing.workspaceId);
          } else {
            await bindCloudWorkspace(editing.workspaceId, newId);
          }
        }
      }
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
    try {
      if (inList) {
        await del(`/api/workspaces/${permWsId}/permissions/${encodeURIComponent(toolName)}`);
      } else {
        await postJson(`/api/workspaces/${permWsId}/permissions/${encodeURIComponent(toolName)}`, {});
      }
    } catch (e: any) {
      setError('切换工具权限失败: ' + (e?.message || e));
    }
    // 无论成功失败都重新拉取，避免乐观状态与后端不一致
    try {
      const rules = await getJson<PermRule[]>(`/api/workspaces/${permWsId}/permissions`);
      setPermRules(rules);
    } catch (e: any) {
      setError('刷新权限列表失败: ' + (e?.message || e));
    }
  };

  return (
    <div className="page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1 className="page-title">
          {typeMeta.icon} {typeMeta.label}工作区
        </h1>
        <button className="btn primary" onClick={() => setShowCreate(!showCreate)}>
          {showCreate ? '取消' : `＋ 新建${typeMeta.label}工作区`}
        </button>
      </div>
      <div className="hint" style={{ marginTop: -8, marginBottom: 12 }}>{typeMeta.hint}</div>

      {error && <div className="error-box">{error}</div>}

      {showCreate && (
        <Modal title={`${typeMeta.icon} 新建${typeMeta.label}工作区`} onClose={() => setShowCreate(false)} width={560}>
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
              <button
                className="btn"
                onClick={browseDir}
                disabled={!guiAvailable}
                title={guiAvailable
                  ? '选择项目目录'
                  : '后端运行环境没有图形界面，无法弹出目录选择窗口（请手动输入完整路径）'}
              >
                📁 浏览
              </button>
            </div>
            {!guiAvailable && (
              <div className="hint" style={{ marginTop: 6 }}>
                当前服务运行环境无图形界面，无法弹出目录选择窗口，请在上方直接输入完整路径。
              </div>
            )}
          </div>
          <div className="field">
            <label>场景（必须是「{typeMeta.label}」类型，决定 AI 在此工作区能做什么、怎么做）</label>
            <select
              value={scenarioName}
              onChange={(e) => setScenarioName(e.target.value)}
              disabled={scenariosForType.length === 0}
            >
              {scenariosForType.length === 0 && (
                <option value="">（暂无{typeMeta.label}类型场景，请先到「工具管理 - 场景」创建）</option>
              )}
              {scenariosForType.map((s) => (
                <option key={s.id} value={s.name}>
                  {s.icon ? `${s.icon} ` : ''}{s.displayName}
                </option>
              ))}
            </select>
            <div className="hint">工作区类型与场景类型强一致；创建后可随时在此页编辑切换为同类型的其他场景</div>
          </div>
          {cloudMode && (
            <div className="field">
              <label>归属项目（cloud 模式必选：知识库/黑板数据归属该 hub 项目）</label>
              <select
                value={projectId}
                onChange={(e) => setProjectId(e.target.value === '' ? '' : Number(e.target.value))}
              >
                <option value="">（请选择项目…）</option>
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}{p.slug ? ` (${p.slug})` : ''}
                  </option>
                ))}
              </select>
              {projects.length === 0 && (
                <div className="hint">⚠️ 项目清单拉取失败或组织下暂无项目，cloud 模式下无法创建工作区</div>
              )}
            </div>
          )}
          {resourceFields(dbKey, setDbKey, opsKey, setOpsKey)}
          <div className="field">
            <label>描述（可选）</label>
            <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={2} />
          </div>
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
            <button className="btn" onClick={() => setShowCreate(false)}>取消</button>
            <button
              className="btn primary"
              onClick={create}
              disabled={scenariosForType.length === 0 || !scenarioName || (cloudMode && projectId === '')}
            >
              创建并初始化
            </button>
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
            <label>场景（仅可切换为「{typeMeta.label}」类型的其他场景）</label>
            <select value={editScenario} onChange={(e) => setEditScenario(e.target.value)}>
              {scenariosForType.length === 0 && (
                <option value="">（暂无{typeMeta.label}类型场景）</option>
              )}
              {scenariosForType.map((s) => (
                <option key={s.id} value={s.name}>
                  {s.icon ? `${s.icon} ` : ''}{s.displayName}
                </option>
              ))}
            </select>
            <div className="hint">切换后立即重建该工作区的 AI，正在进行的对话建议先结束；工作区形态不支持在此变更</div>
          </div>
          {cloudMode && (
            <div className="field">
              <label>归属项目（知识库/黑板数据归属的 hub 项目，可换绑）</label>
              <select
                value={editProjectId}
                onChange={(e) => setEditProjectId(e.target.value === '' ? '' : Number(e.target.value))}
              >
                <option value="">（未绑定）</option>
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}{p.slug ? ` (${p.slug})` : ''}
                  </option>
                ))}
              </select>
              <div className="hint">换绑后该工作区后续写入云端的数据归属新项目；历史数据仍留在原项目</div>
            </div>
          )}
          {resourceFields(editDbKey, setEditDbKey, editOpsKey, setEditOpsKey)}
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
      ) : visibleItems.length === 0 ? (
        <div className="empty">
          <h3>还没有{typeMeta.label}工作区</h3>
          <p className="hint">点击右上角「新建{typeMeta.label}工作区」，指定一个项目目录后即可与 AI 对话</p>
        </div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
          {visibleItems.map((ws) => (
            <div
              key={ws.workspaceId}
              className="card"
              style={{ cursor: 'pointer' }}
              onClick={() => navigate(`/chat/${ws.workspaceId}`)}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span style={{ fontSize: '1.6em' }}>{typeMeta.icon}</span>
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
                {/* ops 工作区白名单机制关闭（AgentOrchestrator.whitelistEnabled=false），不渲染授权入口 */}
                {(ws.type || 'single') !== 'ops' && (
                  <button
                    className="btn small"
                    title="工具白名单"
                    onClick={(e) => { e.stopPropagation(); openPerm(ws.workspaceId); }}
                  >
                    🔐
                  </button>
                )}
                <button
                  className="btn danger small"
                  title="删除工作区"
                  onClick={(e) => { e.stopPropagation(); setDeleting(ws); }}
                >
                  🗑
                </button>
              </div>
              {ws.description && <div className="hint" style={{ marginTop: 8 }}>{ws.description}</div>}
              {cloudMode && (
                <div className="hint" style={{ marginTop: 6 }}>
                  {ws.projectId != null ? `📦 归属项目：${projectName(ws.projectId)}` : '📦 未绑定 hub 项目'}
                  {/* 资源绑定徽章：显示 serverKey（hub 侧 ASCII 标识，稳定唯一）；
                      业务名要另拉候选才能映射，列表页不值得为此多发请求 */}
                  {(ws.dbServerKey || ws.opsServerKey) ? (
                    <>
                      {ws.dbServerKey ? ` · 🗄 ${ws.dbServerKey}` : ''}
                      {ws.opsServerKey ? ` · 🖥 ${ws.opsServerKey}` : ''}
                    </>
                  ) : null}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
