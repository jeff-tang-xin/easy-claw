import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {
  archiveWorkspace,
  createMenu,
  createWorkspace,
  deleteMenu,
  getWorkspace,
  listMenus,
  listProjects,
  listWorkspaces,
  toggleMenu,
  updateMenu,
} from '../api';
import Modal from '../components/Modal';
import type {MenuItemDto, ProjectDto, WorkspaceDto} from '../types';

interface Props {
  orgId: number | null;
}

/**
 * Spoke 工作区与公共菜单配置：工作区是 project 面向 spoke 的扩展面（1:1 绑定），
 * 菜单绑定工作区、与组织无关，同一工作区的菜单对所有 spoke 通用（hub 只配置与下发）。
 * 读/写权限跟随绑定 project 的可见性/编辑权限（服务端裁决）。
 */
export default function WorkspacesPage({orgId}: Props) {
  const navigate = useNavigate();
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[] | null>(null);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [error, setError] = useState('');
  const [showArchived, setShowArchived] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    if (orgId == null) return;
    try {
      const [ws, ps] = await Promise.all([listWorkspaces(orgId), listProjects(orgId)]);
      setWorkspaces(ws);
      setProjects(ps);
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载工作区失败');
    }
  }, [orgId]);

  useEffect(() => {
    setWorkspaces(null);
    void reload();
  }, [reload]);

  const projectName = useMemo(() => {
    const m = new Map<number, string>();
    for (const p of projects) m.set(p.id, p.name);
    return m;
  }, [projects]);

  const visible = useMemo(
    () => (workspaces ?? []).filter((w) => showArchived || w.status !== 'archived'),
    [workspaces, showArchived],
  );

  if (orgId == null) {
    return (
      <div className="page">
        <div className="empty-state">
          <h3>还没有组织</h3>
          <p>工作区归属于组织。请先创建一个组织。</p>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>Spoke 工作区</h2>
          <p className="page-desc">
            工作区是项目面向 spoke 的扩展面（1:1 绑定），为其配置下发给各 spoke 的公共菜单。
          </p>
        </div>
        <div className="page-head-right">
          <label className="toggle-archived">
            <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
            显示已归档
          </label>
          <button type="button" className="btn btn-primary" onClick={() => setCreateOpen(true)}>
            ＋ 新建工作区
          </button>
        </div>
      </div>

      {error && <div className="form-error">{error}</div>}
      {workspaces == null && !error && <div className="empty-hint">加载中…</div>}

      {workspaces != null && visible.length === 0 && (
        <div className="empty-state">
          <h3>{showArchived ? '没有工作区' : '还没有工作区'}</h3>
          <p>点击右上角「新建工作区」，选择一个项目绑定，即可为其配置 spoke 公共菜单。</p>
        </div>
      )}

      <div className="project-grid">
        {visible.map((w) => {
          const archived = w.status === 'archived';
          return (
            <div
              key={w.id}
              className={archived ? 'project-card archived project-card-link' : 'project-card project-card-link'}
              role="button"
              tabIndex={0}
              title="配置菜单"
              onClick={() => navigate(`/workspaces/${w.id}/menus`)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  navigate(`/workspaces/${w.id}/menus`);
                }
              }}
            >
              <div className="project-card-title">
                <span className="project-name">{w.name}</span>
                {archived && <span className="badge badge-archived">已归档</span>}
              </div>
              <div className="project-slug">
                绑定项目：{projectName.get(w.projectId) ?? `#${w.projectId}`} · 菜单 {w.menuCount} 项
              </div>
              <div className="project-meta">
                更新于 {w.updatedAt ? new Date(w.updatedAt).toLocaleString() : '—'}
              </div>
              <div className="project-card-enter">配置菜单 →</div>
            </div>
          );
        })}
      </div>

      {createOpen && (
        <CreateWorkspaceModal
          projects={projects}
          boundProjectIds={new Set((workspaces ?? []).map((w) => w.projectId))}
          onClose={() => setCreateOpen(false)}
          onCreated={() => {
            setCreateOpen(false);
            void reload();
          }}
          setFormError={setFormError}
          formError={formError}
          busy={busy}
          setBusy={setBusy}
        />
      )}
    </div>
  );
}

/** 新建工作区弹窗：选择一个尚无工作区的项目绑定（1:1）。 */
function CreateWorkspaceModal({
  projects,
  boundProjectIds,
  onClose,
  onCreated,
  formError,
  setFormError,
  busy,
  setBusy,
}: {
  projects: ProjectDto[];
  boundProjectIds: Set<number>;
  onClose: () => void;
  onCreated: () => void;
  formError: string;
  setFormError: (s: string) => void;
  busy: boolean;
  setBusy: (b: boolean) => void;
}) {
  const [projectId, setProjectId] = useState<number | ''>('');
  const [name, setName] = useState('');

  const available = projects.filter((p) => p.status !== 'archived' && !boundProjectIds.has(p.id));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    if (projectId === '') {
      setFormError('请选择一个项目');
      return;
    }
    setBusy(true);
    try {
      await createWorkspace(projectId, name);
      onCreated();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '创建工作区失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title="新建工作区" subtitle="工作区与项目 1:1 绑定；一个项目只能绑定一个工作区" onClose={onClose}>
      <form className="modal-form" onSubmit={submit}>
        <label>
          绑定项目
          <select value={projectId} onChange={(e) => setProjectId(Number(e.target.value))} required>
            <option value="">请选择…</option>
            {available.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}（@{p.slug}）
              </option>
            ))}
          </select>
          {available.length === 0 && (
            <span className="field-hint">
              当前组织没有可绑定的项目（已归档或已绑定工作区的项目不在此列出），请先在「项目」中创建。
            </span>
          )}
        </label>
        <label>
          工作区名称（可选，留空取项目名）
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={128} placeholder="留空则使用项目名" />
        </label>
        {formError && <div className="form-error">{formError}</div>}
        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            取消
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? '创建中…' : '创建'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ==================== 菜单配置视图 ====================

/** 菜单项表单字段（新增/编辑共用）。 */
interface MenuForm {
  menuKey: string;
  label: string;
  icon: string;
  path: string;
  requiredPerm: string;
  visibleRoles: string;
  sortOrder: number;
  enabled: boolean;
}

const EMPTY_MENU_FORM: MenuForm = {
  menuKey: '',
  label: '',
  icon: '',
  path: '',
  requiredPerm: '',
  visibleRoles: '',
  sortOrder: 0,
  enabled: true,
};

/** 将扁平菜单项按 parentId 组装成树（保序）。 */
function buildMenuTree(items: MenuItemDto[]): MenuItemDto[] {
  const byParent = new Map<number | null, MenuItemDto[]>();
  for (const m of items) {
    const key = m.parentId;
    if (!byParent.has(key)) byParent.set(key, []);
    byParent.get(key)!.push(m);
  }
  return byParent.get(null) ?? [];
}

/** 菜单配置页：树形展示 + 新增/编辑/启停/删除/排序。 */
export function MenuConfigView({workspaceId}: {workspaceId: number}) {
  const navigate = useNavigate();
  const [workspace, setWorkspace] = useState<WorkspaceDto | null>(null);
  const [items, setItems] = useState<MenuItemDto[] | null>(null);
  const [error, setError] = useState('');
  const [modal, setModal] = useState<null | {mode: 'create'; parentId: number | null} | {mode: 'edit'; item: MenuItemDto}>(
    null,
  );
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    try {
      const [ws, ms] = await Promise.all([getWorkspace(workspaceId), listMenus(workspaceId)]);
      setWorkspace(ws);
      setItems(ms);
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载菜单失败');
    }
  }, [workspaceId]);

  useEffect(() => {
    setItems(null);
    void reload();
  }, [reload]);

  const tree = useMemo(() => (items ? buildMenuTree(items) : []), [items]);

  const onToggle = async (m: MenuItemDto) => {
    try {
      await toggleMenu(m.id, !m.enabled);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : '切换失败');
    }
  };

  const onDelete = async (m: MenuItemDto) => {
    const children = items?.filter((x) => x.parentId === m.id) ?? [];
    const cascade = children.length > 0 ? `（含 ${children.length} 个子项）` : '';
    if (!window.confirm(`确定删除菜单「${m.label}」${cascade}？删除将级联移除其全部子孙。`)) return;
    setBusy(true);
    try {
      await deleteMenu(m.id);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败');
    } finally {
      setBusy(false);
    }
  };

  const onArchive = async () => {
    if (!workspace) return;
    if (!window.confirm(`确定归档工作区「${workspace.name}」？归档将级联删除其全部菜单配置。`)) return;
    setBusy(true);
    try {
      await archiveWorkspace(workspace.id);
      navigate('/workspaces');
    } catch (err) {
      setError(err instanceof Error ? err.message : '归档失败');
    } finally {
      setBusy(false);
    }
  };

  if (error && !items) {
    return (
      <div className="page">
        <div className="form-error">{error}</div>
        <button type="button" className="btn btn-ghost back-btn" onClick={() => navigate('/workspaces')}>
          ← 返回工作区列表
        </button>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <button type="button" className="btn btn-ghost btn-sm back-btn" onClick={() => navigate('/workspaces')}>
            ← 工作区
          </button>
          <h2>{workspace?.name ?? '菜单配置'}</h2>
          <p className="page-desc">
            配置下发给各 spoke 的公共菜单。菜单与组织无关，同一工作区的菜单对所有 spoke 通用。
          </p>
        </div>
        <div className="page-head-right">
          {workspace && workspace.status !== 'archived' && (
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => setModal({mode: 'create', parentId: null})}
            >
              ＋ 新增顶层菜单
            </button>
          )}
          {workspace && (
            <button type="button" className="btn btn-danger" onClick={() => void onArchive()} disabled={busy}>
              归档工作区
            </button>
          )}
        </div>
      </div>

      {error && <div className="form-error">{error}</div>}
      {items == null && !error && <div className="empty-hint">加载中…</div>}

      {items != null && (
        <div className="card">
          {items.length === 0 ? (
            <div className="empty-state">
              <h3>还没有菜单项</h3>
              <p>点击右上角「新增顶层菜单」开始配置 spoke 公共菜单。</p>
            </div>
          ) : (
            <table className="data-table menu-table">
              <thead>
                <tr>
                  <th>菜单</th>
                  <th>标识</th>
                  <th>路径</th>
                  <th>权限</th>
                  <th>排序</th>
                  <th>状态</th>
                  <th className="col-actions">操作</th>
                </tr>
              </thead>
              <tbody>
                {tree.map((m) => (
                  <MenuRow
                    key={m.id}
                    item={m}
                    depth={0}
                    allItems={items}
                    onToggle={onToggle}
                    onEdit={(it) => setModal({mode: 'edit', item: it})}
                    onAddChild={(parentId) => setModal({mode: 'create', parentId})}
                    onDelete={onDelete}
                  />
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {modal && (
        <MenuFormModal
          mode={modal.mode}
          workspaceId={workspaceId}
          parentId={modal.mode === 'create' ? modal.parentId : null}
          item={modal.mode === 'edit' ? modal.item : null}
          allItems={items ?? []}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null);
            void reload();
          }}
        />
      )}
    </div>
  );
}

/** 单行菜单（含递归子项）。 */
function MenuRow({
  item,
  depth,
  allItems,
  onToggle,
  onEdit,
  onAddChild,
  onDelete,
}: {
  item: MenuItemDto;
  depth: number;
  allItems: MenuItemDto[];
  onToggle: (m: MenuItemDto) => void;
  onEdit: (m: MenuItemDto) => void;
  onAddChild: (parentId: number) => void;
  onDelete: (m: MenuItemDto) => void;
}) {
  const children = allItems.filter((x) => x.parentId === item.id);
  return (
    <>
      <tr className={item.enabled ? '' : 'menu-row-disabled'}>
        <td>
          <div className="menu-label" style={{paddingLeft: depth * 22}}>
            {depth > 0 && <span className="menu-tree-guide">└ </span>}
            {item.icon && <span className="menu-icon">{item.icon}</span>}
            <span>{item.label}</span>
            {!item.enabled && <span className="badge badge-archived">停用</span>}
          </div>
        </td>
        <td>
          <code className="muted menu-key">{item.menuKey}</code>
        </td>
        <td className="cell-ellipsis">{item.path || '—'}</td>
        <td>
          {item.requiredPerm ? <code className="muted menu-key">{item.requiredPerm}</code> : <span className="muted">—</span>}
        </td>
        <td>{item.sortOrder}</td>
        <td>
          <button
            type="button"
            className={item.enabled ? 'menu-toggle on' : 'menu-toggle off'}
            onClick={() => onToggle(item)}
            title={item.enabled ? '点击停用' : '点击启用'}
          >
            {item.enabled ? '启用' : '停用'}
          </button>
        </td>
        <td className="col-actions">
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => onAddChild(item.id)} title="新增子菜单">
            ＋子项
          </button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => onEdit(item)}>
            编辑
          </button>
          <button type="button" className="btn btn-ghost btn-sm menu-del" onClick={() => onDelete(item)}>
            删除
          </button>
        </td>
      </tr>
      {children.map((c) => (
        <MenuRow
          key={c.id}
          item={c}
          depth={depth + 1}
          allItems={allItems}
          onToggle={onToggle}
          onEdit={onEdit}
          onAddChild={onAddChild}
          onDelete={onDelete}
        />
      ))}
    </>
  );
}

/** 新增/编辑菜单弹窗。 */
function MenuFormModal({
  mode,
  workspaceId,
  parentId,
  item,
  allItems,
  onClose,
  onSaved,
}: {
  mode: 'create' | 'edit';
  workspaceId: number;
  parentId: number | null;
  item: MenuItemDto | null;
  allItems: MenuItemDto[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form, setForm] = useState<MenuForm>(() =>
    item
      ? {
          menuKey: item.menuKey,
          label: item.label,
          icon: item.icon,
          path: item.path,
          requiredPerm: item.requiredPerm,
          visibleRoles: item.visibleRoles,
          sortOrder: item.sortOrder,
          enabled: item.enabled,
        }
      : EMPTY_MENU_FORM,
  );
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  const parentLabel = parentId != null ? allItems.find((m) => m.id === parentId)?.label : null;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    if (!form.label.trim()) {
      setFormError('菜单名称不能为空');
      return;
    }
    if (form.menuKey && !/^[a-z0-9_-]+$/.test(form.menuKey)) {
      setFormError('menuKey 只能包含小写字母/数字/连字符/下划线');
      return;
    }
    setBusy(true);
    try {
      if (mode === 'create') {
        await createMenu(workspaceId, {
          menuKey: form.menuKey.trim() || undefined,
          label: form.label.trim(),
          icon: form.icon.trim() || undefined,
          path: form.path.trim() || undefined,
          parentId: parentId ?? undefined,
          requiredPerm: form.requiredPerm.trim() || undefined,
          visibleRoles: form.visibleRoles.trim() || undefined,
          sortOrder: form.sortOrder,
          enabled: form.enabled,
        });
      } else if (item) {
        await updateMenu(item.id, {
          menuKey: form.menuKey.trim() || undefined,
          label: form.label.trim(),
          icon: form.icon.trim() || undefined,
          path: form.path.trim() || undefined,
          requiredPerm: form.requiredPerm.trim() || undefined,
          visibleRoles: form.visibleRoles.trim() || undefined,
          sortOrder: form.sortOrder,
          enabled: form.enabled,
        });
      }
      onSaved();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '保存失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={mode === 'create' ? (parentId != null ? '新增子菜单' : '新增顶层菜单') : '编辑菜单'}
      subtitle={parentLabel ? `父菜单：${parentLabel}` : undefined}
      onClose={onClose}
    >
      <form className="modal-form" onSubmit={submit}>
        <label>
          菜单名称
          <input
            value={form.label}
            onChange={(e) => setForm({...form, label: e.target.value})}
            maxLength={128}
            autoFocus
            required
          />
        </label>
        <label>
          标识 menuKey（可选，留空由系统从名称派生）
          <input
            value={form.menuKey}
            onChange={(e) => setForm({...form, menuKey: e.target.value})}
            maxLength={64}
            placeholder="小写字母/数字/连字符/下划线"
          />
          <span className="field-hint">spoke 侧稳定标识，同工作区内唯一。</span>
        </label>
        <div className="menu-form-row">
          <label>
            图标（emoji）
            <input value={form.icon} onChange={(e) => setForm({...form, icon: e.target.value})} maxLength={64} placeholder="如 📁" />
          </label>
          <label>
            路径
            <input value={form.path} onChange={(e) => setForm({...form, path: e.target.value})} maxLength={255} placeholder="如 /docs" />
          </label>
        </div>
        <div className="menu-form-row">
          <label>
            可见性权限码
            <input
              value={form.requiredPerm}
              onChange={(e) => setForm({...form, requiredPerm: e.target.value})}
              maxLength={64}
              placeholder="如 project.read，留空=不要求"
            />
          </label>
          <label>
            可见角色（逗号分隔）
            <input
              value={form.visibleRoles}
              onChange={(e) => setForm({...form, visibleRoles: e.target.value})}
              maxLength={255}
              placeholder="如 owner,admin，留空=全部"
            />
          </label>
        </div>
        <div className="menu-form-row">
          <label>
            排序
            <input
              type="number"
              value={form.sortOrder}
              onChange={(e) => setForm({...form, sortOrder: Number(e.target.value) || 0})}
            />
          </label>
          <label className="menu-enabled-check">
            <input
              type="checkbox"
              checked={form.enabled}
              onChange={(e) => setForm({...form, enabled: e.target.checked})}
            />
            启用（下发时仅含启用项）
          </label>
        </div>
        {formError && <div className="form-error">{formError}</div>}
        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            取消
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? '保存中…' : '保存'}
          </button>
        </div>
      </form>
    </Modal>
  );
}