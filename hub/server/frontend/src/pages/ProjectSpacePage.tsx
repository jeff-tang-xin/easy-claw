import {useCallback, useEffect, useState} from 'react';
import {NavLink, Navigate, Route, Routes, useLocation, useNavigate, useParams} from 'react-router-dom';
import {
  archiveProject,
  bindProjectResources,
  getProject,
  listDbConnections,
  listOpsServers,
  restoreProject,
  updateProject,
} from '../api';
import type {DbConnectionDto, OpsServerDto, ProjectDto} from '../types';
import BlackboardPage from './BlackboardPage';
import KnowledgePage from './KnowledgePage';

interface OrgRef {
  id: number;
  name: string;
  role: string;
}

interface Props {
  /** 当前用户加入的全部组织（含角色），用于按项目所属组织推导角色，支持跨组织 public 直链 */
  orgs: OrgRef[];
  meUserId: number;
  /** 是否平台管理员：资源目录（db 连接/ops 服务器）仅平台管理员可读，绑定编辑仅其可用 */
  platformAdmin: boolean;
}

const VIS_LABELS: Record<string, string> = {
  private: '私有',
  team: '团队',
  public: '公开',
};

/**
 * 项目空间（IA 乙方案核心）：/projects/:pid 独立空间，内置二级导航。
 * 概览/知识库/黑板当前可用；设置为项目管理入口，业务模块均落此空间内、不新增顶层菜单。
 */
export default function ProjectSpacePage({orgs, meUserId, platformAdmin}: Props) {
  const {pid} = useParams();
  const projectId = Number(pid);
  const navigate = useNavigate();
  const [project, setProject] = useState<ProjectDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      // 直查单项目，服务端按可见性裁决（组织外用户可读 public 项目）
      setProject(await getProject(projectId));
      setError('');
    } catch (err) {
      setProject(null);
      setError(err instanceof Error ? err.message : '加载项目失败');
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    if (!Number.isFinite(projectId) || projectId <= 0) {
      setLoading(false);
      setError('无效的项目 ID');
      return;
    }
    setLoading(true);
    void load();
  }, [load, projectId]);

  // 按项目所属组织推导角色；未加入该组织（跨组织 public 访问者）为 null → 只读
  const role = orgs.find((o) => o.id === project?.orgId)?.role ?? null;

  if (loading) {
    return (
      <div className="page">
        <div className="empty-hint">加载中…</div>
      </div>
    );
  }
  if (error) {
    return (
      <div className="page">
        <div className="form-error">{error}</div>
      </div>
    );
  }
  if (!project) {
    return (
      <div className="page">
        <div className="empty-state">
          <h3>无法查看该项目</h3>
          <p>项目不存在、已归档后被隐藏，或你没有访问权限。</p>
          <button type="button" className="btn btn-primary" onClick={() => navigate('/projects')}>
            返回项目列表
          </button>
        </div>
      </div>
    );
  }

  const mine = project.ownerUserId === meUserId;
  const canManage = role === 'owner' || role === 'admin' || mine;
  const base = `/projects/${project.id}`;
  const orgName = orgs.find((o) => o.id === project.orgId)?.name ?? '组织';

  return (
    <div className="page project-space">
      <div className="ps-breadcrumb">
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => navigate('/projects')}>
          ← 项目
        </button>
        <span className="crumb-sep">/</span>
        <span className="crumb-text">{orgName}</span>
        <span className="crumb-sep">/</span>
        <span className="crumb-current">{project.name}</span>
      </div>

      <div className="ps-header">
        <div className="ps-title-row">
          <h2>{project.name}</h2>
          <span className={`badge vis-${project.visibility}`}>
            {VIS_LABELS[project.visibility] ?? project.visibility}
          </span>
          {project.status === 'archived' && <span className="badge badge-archived">已归档</span>}
          {mine && <span className="badge badge-current">我创建的</span>}
        </div>
        <p className="page-desc">
          @{project.slug}
          {project.description ? ` · ${project.description}` : ''}
        </p>
      </div>

      <nav className="ps-tabs">
        <NavLink to={base} end className={({isActive}) => (isActive ? 'tab-btn active' : 'tab-btn')}>
          概览
        </NavLink>
        <NavLink to={`${base}/knowledge`} className={({isActive}) => (isActive ? 'tab-btn active' : 'tab-btn')}>
          知识库
        </NavLink>
        <NavLink to={`${base}/blackboard`} className={({isActive}) => (isActive ? 'tab-btn active' : 'tab-btn')}>
          黑板
        </NavLink>
        <NavLink to={`${base}/settings`} className={({isActive}) => (isActive ? 'tab-btn active' : 'tab-btn')}>
          设置
        </NavLink>
      </nav>

      <Routes>
        <Route index element={<OverviewTab project={project} role={role} meUserId={meUserId} />} />
        <Route
          path="knowledge/*"
          element={<KnowledgePage project={project} role={role} meUserId={meUserId} />}
        />
        <Route
          path="blackboard"
          element={<BlackboardPage project={project} role={role} meUserId={meUserId} />}
        />
        <Route
          path="settings"
          element={
            <SettingsTab
              project={project}
              canManage={canManage}
              platformAdmin={platformAdmin}
              onChanged={(np) => setProject(np)}
            />
          }
        />
        <Route path="*" element={<Navigate to={base} replace />} />
      </Routes>
    </div>
  );
}

function OverviewTab({project, role, meUserId}: {project: ProjectDto; role: string | null; meUserId: number}) {
  const mine = project.ownerUserId === meUserId;
  return (
    <div className="ps-overview">
      <div className="card">
        <h3 className="ps-card-title">项目概览</h3>
        <div className="detail-grid" style={{gridTemplateColumns: '90px 1fr'}}>
          <span className="detail-label">标识</span>
          <span>@{project.slug}</span>
          <span className="detail-label">可见性</span>
          <span>{VIS_LABELS[project.visibility] ?? project.visibility}</span>
          <span className="detail-label">状态</span>
          <span>{project.status === 'archived' ? '已归档' : '进行中'}</span>
          <span className="detail-label">创建者</span>
          <span>
            {project.ownerUsername || `#${project.ownerUserId}`}
            {mine && <span className="badge badge-current">我</span>}
          </span>
          <span className="detail-label">更新于</span>
          <span>{project.updatedAt ? new Date(project.updatedAt).toLocaleString() : '—'}</span>
        </div>
      </div>
      <div className="ps-quicknav">
        <div className="ps-quick-item">
          <div className="ps-quick-title">📚 知识库</div>
          <div className="ps-quick-desc">项目级知识条目，支持乐观锁并发与版本历史。</div>
        </div>
        <div className="ps-quick-item">
          <div className="ps-quick-title">📋 黑板</div>
          <div className="ps-quick-desc">项目共享记录本，多 Agent 协作交接。</div>
        </div>
      </div>
      {role === 'guest' && (
        <div className="form-error" style={{marginTop: 14}}>
          访客（guest）为只读角色：可查看项目与知识库/黑板，但不能创建或编辑。
        </div>
      )}
    </div>
  );
}

const SETTINGS_VIS_OPTIONS = ['team', 'private', 'public'];
const SETTINGS_VIS_HINTS: Record<string, string> = {
  private: '仅你本人与组织 owner/admin 可见，其他成员不可见。',
  team: '本组织所有成员可见（含 guest 只读）。',
  public: '本组织成员可见；组织外已登录用户也可只读查看。',
};

/**
 * 项目设置（IA 乙方案）：项目编辑/归档/恢复的唯一入口，从项目列表卡片迁入。
 * 仅 owner/admin 或项目创建者可管理（与后端 requireCanEdit 同口径）；其余角色只读提示。
 */
function SettingsTab({
  project,
  canManage,
  platformAdmin,
  onChanged,
}: {
  project: ProjectDto;
  canManage: boolean;
  platformAdmin: boolean;
  onChanged: (p: ProjectDto) => void;
}) {
  const navigate = useNavigate();
  const archived = project.status === 'archived';
  const [name, setName] = useState(project.name);
  const [description, setDescription] = useState(project.description ?? '');
  const [visibility, setVisibility] = useState(project.visibility);
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState('');
  const [savedHint, setSavedHint] = useState('');

  if (!canManage) {
    return (
      <div className="ps-settings">
        <div className="card">
          <h3 className="ps-card-title">项目设置</h3>
          <div className="form-error">你不是组织 owner/admin 或项目创建者，仅可查看项目信息，不能修改。</div>
        </div>
      </div>
    );
  }

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    setSavedHint('');
    if (!name.trim()) {
      setFormError('项目名称不能为空');
      return;
    }
    setBusy(true);
    try {
      const updated = await updateProject(project.id, {
        name: name.trim(),
        description: description.trim(),
        visibility,
      });
      onChanged(updated);
      setSavedHint('已保存');
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '保存失败');
    } finally {
      setBusy(false);
    }
  };

  const archive = async () => {
    if (!window.confirm(`确定归档项目「${project.name}」？归档后不再出现在默认列表，可在此恢复。`)) return;
    setFormError('');
    try {
      await archiveProject(project.id);
      onChanged({...project, status: 'archived'});
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '归档失败');
    }
  };

  const restore = async () => {
    setFormError('');
    try {
      await restoreProject(project.id);
      onChanged({...project, status: 'active'});
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '恢复失败');
    }
  };

  return (
    <div className="ps-settings">
      <form className="card" onSubmit={save}>
        <h3 className="ps-card-title">基本信息</h3>
        <label>
          项目名称
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={128} required />
        </label>
        <label>
          描述（可选）
          <textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={500} />
        </label>
        <label>
          可见性
          <select value={visibility} onChange={(e) => setVisibility(e.target.value)}>
            {SETTINGS_VIS_OPTIONS.map((v) => (
              <option key={v} value={v}>
                {VIS_LABELS[v] ?? v}
              </option>
            ))}
          </select>
          <span className="field-hint">{SETTINGS_VIS_HINTS[visibility]}</span>
        </label>
        {formError && <div className="form-error">{formError}</div>}
        <div className="modal-actions">
          {savedHint && !formError && <span className="ps-saved-hint">{savedHint}</span>}
          <button type="submit" className="btn btn-primary" disabled={busy || archived}>
            {busy ? '保存中…' : '保存修改'}
          </button>
        </div>
        {archived && <div className="form-error" style={{marginTop: 10}}>项目已归档，不可编辑基本信息；请先恢复。</div>}
      </form>

      <ResourceBindingCard project={project} canManage={canManage} platformAdmin={platformAdmin} onChanged={onChanged} />

      <div className="card ps-danger-zone">
        <h3 className="ps-card-title">归档与恢复</h3>
        <p className="page-desc">归档后项目不再出现在默认列表，历史文档保留；可随时恢复为进行中。</p>
        {archived ? (
          <button type="button" className="btn" onClick={() => void restore()}>
            恢复为进行中
          </button>
        ) : (
          <button type="button" className="btn btn-danger" onClick={() => void archive()}>
            归档此项目
          </button>
        )}
        <div style={{marginTop: 12}}>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => navigate('/projects')}>
            返回项目列表
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * 项目资源绑定（V31 N:N）：该项目绑定的数据库连接与运维服务器（按 serverKey 整组覆盖）。
 * 候选清单来自平台目录（platformAdmin 端点）：platformAdmin 可编辑；组织 owner/admin
 * 只读展示已绑定清单（资源目录对非平台管理员不可读，绑定操作由平台管理员执行）。
 */
function ResourceBindingCard({
  project,
  canManage,
  platformAdmin,
  onChanged,
}: {
  project: ProjectDto;
  canManage: boolean;
  platformAdmin: boolean;
  onChanged: (p: ProjectDto) => void;
}) {
  const [dbConns, setDbConns] = useState<DbConnectionDto[] | null>(null);
  const [opsServers, setOpsServers] = useState<OpsServerDto[] | null>(null);
  const [loadError, setLoadError] = useState('');
  const [selectedDb, setSelectedDb] = useState<Set<string>>(new Set(project.dbServerKeys ?? []));
  const [selectedOps, setSelectedOps] = useState<Set<string>>(new Set(project.opsServerKeys ?? []));
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState('');
  const [savedHint, setSavedHint] = useState('');

  const editable = canManage && platformAdmin;

  useEffect(() => {
    if (!editable) return;
    let cancelled = false;
    Promise.all([listDbConnections(), listOpsServers()])
      .then(([dbs, ops]) => {
        if (cancelled) return;
        // 仅本组织资源可绑（后端同样校验跨组织绑定拒绝）；含未归属（orgId=0）行不可绑
        setDbConns(dbs.filter((c) => c.orgId === project.orgId));
        setOpsServers(ops.filter((s) => s.orgId === project.orgId));
        setLoadError('');
      })
      .catch((err) => {
        if (!cancelled) setLoadError(err instanceof Error ? err.message : '资源目录加载失败');
      });
    return () => {
      cancelled = true;
    };
  }, [editable, project.orgId]);

  if (!canManage) {
    return null;
  }

  const toggle = (set: Set<string>, key: string, apply: (s: Set<string>) => void) => {
    const next = new Set(set);
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    apply(next);
    setSavedHint('');
  };

  const save = async () => {
    setFormError('');
    setSavedHint('');
    setBusy(true);
    try {
      const updated = await bindProjectResources(project.id, {
        dbServerKeys: [...selectedDb],
        opsServerKeys: [...selectedOps],
      });
      onChanged(updated);
      setSavedHint('绑定已保存');
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '保存失败');
    } finally {
      setBusy(false);
    }
  };

  const boundDb = project.dbServerKeys ?? [];
  const boundOps = project.opsServerKeys ?? [];

  return (
    <div className="card">
      <h3 className="ps-card-title">资源绑定</h3>
      <p className="page-desc">
        绑定后，本项目的工作区可在资源选择器中看到对应数据库连接与运维服务器；
        未绑定任何项目（空绑定）的资源对所有项目可见。
      </p>
      {!platformAdmin && (
        <div className="form-error">
          资源绑定由平台管理员维护。当前绑定：
          {boundDb.length === 0 && boundOps.length === 0
            ? '无'
            : `${boundDb.length} 个数据库连接 / ${boundOps.length} 个运维服务器`}
        </div>
      )}
      {platformAdmin && loadError && <div className="form-error">{loadError}</div>}
      {editable && !loadError && (
        <>
          <label className="ps-bind-group">
            <b>数据库连接</b>
            {(dbConns ?? []).length === 0 && <span className="field-hint">本组织暂无数据库连接（在平台目录中维护）</span>}
            {(dbConns ?? []).map((c) => (
              <label key={c.serverKey} className="checkbox-label">
                <input
                  type="checkbox"
                  checked={selectedDb.has(c.serverKey)}
                  onChange={() => toggle(selectedDb, c.serverKey, setSelectedDb)}
                />
                {c.name}（{c.dbType} · {c.serverKey}）
              </label>
            ))}
          </label>
          <label className="ps-bind-group">
            <b>运维服务器</b>
            {(opsServers ?? []).length === 0 && <span className="field-hint">本组织暂无运维服务器（在平台目录中维护）</span>}
            {(opsServers ?? []).map((s) => (
              <label key={s.serverKey} className="checkbox-label">
                <input
                  type="checkbox"
                  checked={selectedOps.has(s.serverKey)}
                  onChange={() => toggle(selectedOps, s.serverKey, setSelectedOps)}
                />
                {s.name}（{s.category || '未标注'} · {s.serverKey}）
              </label>
            ))}
          </label>
          {formError && <div className="form-error">{formError}</div>}
          <div className="modal-actions">
            {savedHint && !formError && <span className="ps-saved-hint">{savedHint}</span>}
            <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void save()}>
              {busy ? '保存中…' : '保存绑定'}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
