import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {createProject, listProjects, listMyOrgs, listOrgOptions, transferProject} from '../api';
import Modal from '../components/Modal';
import type {OrgOptionDto, ProjectDto} from '../types';

interface Props {
  orgId: number | null;
  role: string | null;
  meUserId: number;
  /** 平台管理员：可跨组织迁移项目（目标组织候选拉全量组织） */
  platformAdmin: boolean;
  /** 一个组织都没有时，跳去创建组织前重新校准 me */
  onOrgsNeeded: () => void;
}

// 与后端 Visibility 对齐：仅 private / team / public 三档（历史上前端误出现过 'org'，后端 400）
const VIS_LABELS: Record<string, string> = {
  private: '私有',
  team: '团队',
  public: '公开',
};
const VIS_OPTIONS = ['team', 'private', 'public'];
const VIS_HINTS: Record<string, string> = {
  private: '仅你本人与组织 owner/admin 可见，其他成员不可见。',
  team: '本组织所有成员可见（含 guest 只读）。',
  public: '本组织成员可见；组织外已登录用户也可只读查看。',
};

interface ProjectForm {
  name: string;
  description: string;
  visibility: string;
}

const EMPTY_FORM: ProjectForm = {name: '', description: '', visibility: 'team'};

/** 项目列表：卡片 + 弹窗新建/编辑；归档/恢复在卡片上。slug 由服务端从名称派生，前端不填。 */
export default function ProjectsPage({orgId, role, meUserId, platformAdmin, onOrgsNeeded}: Props) {
  const navigate = useNavigate();
  const [projects, setProjects] = useState<ProjectDto[] | null>(null);
  const [error, setError] = useState('');
  const [showArchived, setShowArchived] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState<ProjectForm>(EMPTY_FORM);
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  const canCreate = role !== null && role !== 'guest';
  // 源组织 owner/admin 或平台管理员可迁移项目（member/guest 均不可）
  const canTransfer = role === 'owner' || role === 'admin' || platformAdmin;

  const [transferTarget, setTransferTarget] = useState<ProjectDto | null>(null);
  const [orgOptions, setOrgOptions] = useState<OrgOptionDto[] | null>(null);
  const [transferOrgId, setTransferOrgId] = useState<number | ''>('');
  const [transferError, setTransferError] = useState('');
  const [transferBusy, setTransferBusy] = useState(false);

  const reload = useCallback(async () => {
    if (orgId == null) return;
    try {
      setProjects(await listProjects(orgId));
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载项目失败');
    }
  }, [orgId]);

  useEffect(() => {
    setProjects(null);
    void reload();
  }, [reload]);

  const visible = useMemo(
    () => (projects ?? []).filter((p) => showArchived || p.status !== 'archived'),
    [projects, showArchived],
  );

  const openCreate = () => {
    setForm(EMPTY_FORM);
    setFormError('');
    setCreateOpen(true);
  };

  const submitCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (orgId == null) return;
    setFormError('');
    setBusy(true);
    try {
      await createProject({
        orgId,
        name: form.name.trim(),
        description: form.description.trim() || null,
        visibility: form.visibility,
      });
      setCreateOpen(false);
      await reload();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '创建失败');
    } finally {
      setBusy(false);
    }
  };

  /** 打开迁移弹窗：加载目标组织候选。平台管理员拉全量组织；普通 owner/admin 拉「我所在的组织」，过滤掉当前组织。 */
  const openTransfer = async (p: ProjectDto) => {
    setTransferTarget(p);
    setTransferOrgId('');
    setTransferError('');
    setOrgOptions(null);
    try {
      if (platformAdmin) {
        const orgs = await listOrgOptions();
        setOrgOptions(orgs.filter((o) => o.id !== p.orgId));
      } else {
        const orgs = await listMyOrgs();
        setOrgOptions(orgs.filter((o) => o.id !== p.orgId));
      }
    } catch (err) {
      setTransferError(err instanceof Error ? err.message : '加载组织列表失败');
    }
  };

  const submitTransfer = async (e: React.FormEvent) => {
    e.preventDefault();
    if (transferTarget == null || transferOrgId === '') return;
    setTransferError('');
    setTransferBusy(true);
    try {
      await transferProject(transferTarget.id, transferOrgId);
      setTransferTarget(null);
      await reload();
    } catch (err) {
      setTransferError(err instanceof Error ? err.message : '迁移失败');
    } finally {
      setTransferBusy(false);
    }
  };

  if (orgId == null) {
    return (
      <div className="page">
        <div className="empty-state">
          <h3>还没有组织</h3>
          <p>项目是归属于组织的。请先创建一个组织。</p>
          <a className="btn btn-primary" href="/orgs" onClick={onOrgsNeeded}>
            去创建组织
          </a>
        </div>
      </div>
    );
  }

  const renderFormModal = (
    title: string,
    subtitle: string,
    onSubmit: (e: React.FormEvent) => void,
    onClose: () => void,
    submitLabel: string,
  ) => (
    <Modal title={title} subtitle={subtitle} onClose={onClose}>
      <form className="modal-form" onSubmit={onSubmit}>
        <label>
          项目名称
          <input
            value={form.name}
            onChange={(e) => setForm({...form, name: e.target.value})}
            maxLength={128}
            autoFocus
            required
          />
        </label>
        <label>
          描述（可选）
          <textarea
            rows={3}
            value={form.description}
            onChange={(e) => setForm({...form, description: e.target.value})}
            maxLength={500}
          />
        </label>
        <label>
          可见性
          <select value={form.visibility} onChange={(e) => setForm({...form, visibility: e.target.value})}>
            {VIS_OPTIONS.map((v) => (
              <option key={v} value={v}>
                {VIS_LABELS[v] ?? v}
              </option>
            ))}
          </select>
          <span className="field-hint">{VIS_HINTS[form.visibility]}</span>
        </label>
        {formError && <div className="form-error">{formError}</div>}
        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            取消
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? '提交中…' : submitLabel}
          </button>
        </div>
      </form>
    </Modal>
  );

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>项目</h2>
          <p className="page-desc">当前组织下的全部项目；项目是知识库 / 黑板的归类锚点。</p>
        </div>
        <div className="page-head-right">
          <label className="toggle-archived">
            <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
            显示已归档
          </label>
          {canCreate && (
            <button type="button" className="btn btn-primary" onClick={openCreate}>
              ＋ 新建项目
            </button>
          )}
        </div>
      </div>

      {error && <div className="form-error">{error}</div>}
      {projects == null && !error && <div className="empty-hint">加载中…</div>}

      {projects != null && visible.length === 0 && (
        <div className="empty-state">
          <h3>{showArchived ? '没有项目' : '还没有进行中的项目'}</h3>
          <p>{canCreate ? '点击右上角「新建项目」开始。' : '请联系组织管理员创建项目。'}</p>
        </div>
      )}

      <div className="project-grid">
        {visible.map((p) => {
          const archived = p.status === 'archived';
          const mine = p.ownerUserId === meUserId;
          return (
            <div
              key={p.id}
              className={archived ? 'project-card archived project-card-link' : 'project-card project-card-link'}
              role="button"
              tabIndex={0}
              title="进入项目空间"
              onClick={() => navigate(`/projects/${p.id}`)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  navigate(`/projects/${p.id}`);
                }
              }}
            >
              <div className="project-card-title">
                <span className="project-name">{p.name}</span>
                <span className={`badge vis-${p.visibility}`}>{VIS_LABELS[p.visibility] ?? p.visibility}</span>
                {archived && <span className="badge badge-archived">已归档</span>}
                {mine && <span className="badge badge-current">我创建的</span>}
              </div>
              <div className="project-slug">@{p.slug}</div>
              {p.description && <p className="project-desc">{p.description}</p>}
              <div className="project-meta">
                创建者 {p.ownerUsername || `#${p.ownerUserId}`}
                {mine ? '（我）' : ''} · 更新于 {p.updatedAt ? new Date(p.updatedAt).toLocaleString() : '—'}
              </div>
              <div className="project-card-actions">
                {canTransfer && !archived && (
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    title="迁移到其他组织（仅 owner/admin/平台管理员）"
                    onClick={(e) => {
                      e.stopPropagation();
                      void openTransfer(p);
                    }}
                  >
                    迁移
                  </button>
                )}
                <span className="project-card-enter">进入空间 →</span>
              </div>
            </div>
          );
        })}
      </div>

      {createOpen &&
        renderFormModal('新建项目', '标识（slug）由系统根据名称自动生成', submitCreate, () => setCreateOpen(false), '创建')}

      {transferTarget && (
        <Modal
          title="迁移项目"
          subtitle={`将「${transferTarget.name}」迁移到其他组织（仅 owner/admin/平台管理员可操作）`}
          onClose={() => setTransferTarget(null)}
        >
          <form className="modal-form" onSubmit={submitTransfer}>
            <label>
              目标组织
              <select
                value={transferOrgId}
                onChange={(e) => setTransferOrgId(e.target.value === '' ? '' : Number(e.target.value))}
                required
              >
                <option value="" disabled>
                  {orgOptions == null ? '加载中…' : '请选择目标组织'}
                </option>
                {(orgOptions ?? []).map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}（@{o.slug}）
                  </option>
                ))}
              </select>
              <span className="field-hint">
                迁移后项目归属目标组织，创建者改为你；目标组织已存在同名 slug 时将被拒绝。
              </span>
            </label>
            {transferError && <div className="form-error">{transferError}</div>}
            <div className="modal-actions">
              <button type="button" className="btn btn-ghost" onClick={() => setTransferTarget(null)}>
                取消
              </button>
              <button type="submit" className="btn btn-primary" disabled={transferBusy || transferOrgId === ''}>
                {transferBusy ? '迁移中…' : '确认迁移'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
