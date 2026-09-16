import {useCallback, useEffect, useState} from 'react';
import {NavLink, Navigate, Route, Routes, useNavigate, useParams} from 'react-router-dom';
import {getProject} from '../api';
import type {ProjectDto} from '../types';
import DocsPage from './DocsPage';

interface OrgRef {
  id: number;
  role: string;
}

interface Props {
  /** 当前用户加入的全部组织（含角色），用于按项目所属组织推导角色，支持跨组织 public 直链 */
  orgs: OrgRef[];
  meUserId: number;
}

const VIS_LABELS: Record<string, string> = {
  private: '私有',
  team: '团队',
  public: '公开',
};

/**
 * 项目空间（IA 乙方案核心）：/projects/:pid 独立空间，内置二级导航。
 * 概览/文档当前可用；知识库/黑板/设置为后续模块占位，业务模块均落此空间内、不新增顶层菜单。
 */
export default function ProjectSpacePage({orgs, meUserId}: Props) {
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
  const base = `/projects/${project.id}`;

  return (
    <div className="page project-space">
      <div className="ps-breadcrumb">
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => navigate('/projects')}>
          ← 项目
        </button>
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
        <NavLink to={`${base}/docs`} className={({isActive}) => (isActive ? 'tab-btn active' : 'tab-btn')}>
          文档
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
        <Route path="docs/*" element={<DocsPage project={project} role={role} meUserId={meUserId} />} />
        <Route
          path="knowledge"
          element={<PlaceholderTab title="知识库" desc="A3 将在此提供项目级知识库与语义检索。" />}
        />
        <Route
          path="blackboard"
          element={<PlaceholderTab title="黑板" desc="A4 将在此提供项目共享任务板（多 Agent 协作交接）。" />}
        />
        <Route
          path="settings"
          element={<PlaceholderTab title="设置" desc="项目设置将在此提供（名称/描述/可见性/归档）。" />}
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
            用户 #{project.ownerUserId}
            {mine && <span className="badge badge-current">我</span>}
          </span>
          <span className="detail-label">更新于</span>
          <span>{project.updatedAt ? new Date(project.updatedAt).toLocaleString() : '—'}</span>
        </div>
      </div>
      <div className="ps-quicknav">
        <div className="ps-quick-item">
          <div className="ps-quick-title">📄 文档</div>
          <div className="ps-quick-desc">需求 / 任务协作，支持乐观锁并发与版本历史。</div>
        </div>
        <div className="ps-quick-item ps-quick-disabled">
          <div className="ps-quick-title">📚 知识库</div>
          <div className="ps-quick-desc">A3 规划中。</div>
        </div>
        <div className="ps-quick-item ps-quick-disabled">
          <div className="ps-quick-title">📋 黑板</div>
          <div className="ps-quick-desc">A4 规划中。</div>
        </div>
      </div>
      {role === 'guest' && (
        <div className="form-error" style={{marginTop: 14}}>
          访客（guest）为只读角色：可查看项目与文档，但不能创建或编辑。
        </div>
      )}
    </div>
  );
}

function PlaceholderTab({title, desc}: {title: string; desc: string}) {
  return (
    <div className="empty-state">
      <h3>{title}</h3>
      <p>{desc}</p>
    </div>
  );
}
