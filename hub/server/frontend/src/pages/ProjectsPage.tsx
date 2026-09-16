import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {archiveProject, createProject, listProjects, restoreProject, updateProject} from '../api';
import Modal from '../components/Modal';
import type {ProjectDto} from '../types';

interface Props {
  orgId: number | null;
  role: string | null;
  meUserId: number;
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
export default function ProjectsPage({orgId, role, meUserId, onOrgsNeeded}: Props) {
  const navigate = useNavigate();
  const [projects, setProjects] = useState<ProjectDto[] | null>(null);
  const [error, setError] = useState('');
  const [showArchived, setShowArchived] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<ProjectDto | null>(null);
  const [form, setForm] = useState<ProjectForm>(EMPTY_FORM);
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  const canEdit = role === 'owner' || role === 'admin';
  const canCreate = role !== null && role !== 'guest';

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

  const openEdit = (p: ProjectDto) => {
    setForm({name: p.name, description: p.description ?? '', visibility: p.visibility});
    setFormError('');
    setEditing(p);
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

  const submitEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editing) return;
    setFormError('');
    setBusy(true);
    try {
      await updateProject(editing.id, {
        name: form.name.trim(),
        description: form.description.trim(),
        visibility: form.visibility,
      });
      setEditing(null);
      await reload();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '保存失败');
    } finally {
      setBusy(false);
    }
  };

  const archive = async (p: ProjectDto) => {
    if (!window.confirm(`确定归档项目「${p.name}」？归档后不再出现在默认列表。`)) return;
    try {
      await archiveProject(p.id);
      await reload();
    } catch (err) {
      alert(err instanceof Error ? err.message : '归档失败');
    }
  };

  const restore = async (p: ProjectDto) => {
    try {
      await restoreProject(p.id);
      await reload();
    } catch (err) {
      alert(err instanceof Error ? err.message : '恢复失败');
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
          <p className="page-desc">当前组织下的全部项目；项目是 docs / 知识库 / 记录本的归类锚点。</p>
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
                创建者 #{p.ownerUserId}
                {mine ? '（我）' : ''} · 更新于 {p.updatedAt ? new Date(p.updatedAt).toLocaleString() : '—'}
              </div>
              {(canEdit || mine) && (
                <div className="row-actions" onClick={(e) => e.stopPropagation()}>
                  {!archived && (
                    <button type="button" className="btn btn-sm" onClick={() => openEdit(p)}>
                      编辑
                    </button>
                  )}
                  {!archived ? (
                    <button type="button" className="btn btn-sm btn-danger" onClick={() => void archive(p)}>
                      归档
                    </button>
                  ) : (
                    <button type="button" className="btn btn-sm" onClick={() => void restore(p)}>
                      恢复
                    </button>
                  )}
                </div>
              )}
              <div className="project-card-enter">进入空间 →</div>
            </div>
          );
        })}
      </div>

      {createOpen &&
        renderFormModal('新建项目', '标识（slug）由系统根据名称自动生成', submitCreate, () => setCreateOpen(false), '创建')}
      {editing && renderFormModal('编辑项目', `@${editing.slug}`, submitEdit, () => setEditing(null), '保存')}
    </div>
  );
}
