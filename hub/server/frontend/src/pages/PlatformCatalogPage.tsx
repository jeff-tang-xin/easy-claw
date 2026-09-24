import {useCallback, useEffect, useMemo, useState} from 'react';
import {
  adminListUsers,
  createOpsServer,
  createOpsServerGrant,
  createPlatformFlag,
  createPlatformMenu,
  deleteOpsGrant,
  deleteOpsServer,
  deletePlatformFlag,
  deletePlatformMenu,
  listOpsServerCommandLogs,
  listOpsServerGrants,
  listOpsServers,
  listOrgOptions,
  listPlatformFlags,
  listPlatformMenus,
  listPlatformTools,
  listProjects,
  setPlatformToolEnabled,
  updateOpsServer,
  updatePlatformFlag,
  updatePlatformMenu,
} from '../api';
import Modal from '../components/Modal';
import type {
  FeatureFlagDto,
  MenuItemDto,
  OpsCommandLogDto,
  OpsCommandLogPage,
  OpsServerDto,
  OpsServerGrantDto,
  OrgOptionDto,
  PlatformToolDto,
  ProjectDto,
  UserDto,
} from '../types';

interface Props {
  /** 当前用户是否平台管理员（/api/me 下发）；非平台管理员显示无权限空态 */
  platformAdmin: boolean;
}

type TabKey = 'menus' | 'flags' | 'tools' | 'ops';

const TABS: {key: TabKey; label: string}[] = [
  {key: 'menus', label: '菜单'},
  {key: 'flags', label: '功能开关'},
  {key: 'tools', label: '工具'},
  {key: 'ops', label: '运维服务器'},
];

/**
 * 平台目录（platformAdmin 专属）：菜单/功能开关/工具全部平台级内置，
 * 组织侧只能决定「菜单是否显示」「开关/工具是否启用」，目录本身只在这里维护。
 * 工具为只读清单（对齐 web/api ToolRegistry），仅平台总开关可改。
 * 运维服务器为平台管理员维护的目录条目：归属组织/项目后按归属下发给 spoke，并按用户时效授权。
 */
export default function PlatformCatalogPage({platformAdmin}: Props) {
  const [tab, setTab] = useState<TabKey>('menus');

  if (!platformAdmin) {
    return (
      <div className="page">
        <div className="empty-state">
          <h3>无权限</h3>
          <p>平台目录仅平台管理员可管理。</p>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>平台目录</h2>
          <p className="page-desc">
            平台级内置目录：菜单、功能开关与工具。组织侧只能调整可见性/启用开关，目录条目只在这里增删改。
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

      {tab === 'menus' && <PlatformMenusTab />}
      {tab === 'flags' && <PlatformFlagsTab />}
      {tab === 'tools' && <PlatformToolsTab />}
      {tab === 'ops' && <PlatformOpsServersTab />}
    </div>
  );
}

// ============ 菜单 tab：树形 CRUD ============

function PlatformMenusTab() {
  const [items, setItems] = useState<MenuItemDto[] | null>(null);
  const [error, setError] = useState('');
  const [modal, setModal] = useState<null | {mode: 'create'; parentId: number | null} | {mode: 'edit'; item: MenuItemDto}>(
    null,
  );
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    try {
      setItems(await listPlatformMenus());
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载菜单目录失败');
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const tree = useMemo(() => (items ? buildMenuTree(items) : []), [items]);

  const onToggle = async (m: MenuItemDto) => {
    try {
      await updatePlatformMenu(m.id, {
        label: m.label,
        icon: m.icon || undefined,
        path: m.path || undefined,
        requiredPerm: m.requiredPerm || undefined,
        visibleRoles: m.visibleRoles || undefined,
        sortOrder: m.sortOrder,
        enabled: !m.enabled,
      });
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
      await deletePlatformMenu(m.id);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败');
    } finally {
      setBusy(false);
    }
  };

  if (error && !items) {
    return <div className="form-error">{error}</div>;
  }

  return (
    <>
      {error && <div className="form-error">{error}</div>}
      {items == null && !error && <div className="empty-hint">加载中…</div>}

      {items != null && (
        <div className="card">
          <div className="page-head" style={{marginBottom: 8}}>
            <span className="muted">共 {items.length} 项</span>
            <div className="page-head-right">
              <button type="button" className="btn btn-primary" onClick={() => setModal({mode: 'create', parentId: null})}>
                ＋ 新增顶层菜单
              </button>
            </div>
          </div>
          {items.length === 0 ? (
            <div className="empty-state">
              <h3>菜单目录为空</h3>
              <p>点击右上角「新增顶层菜单」开始配置平台菜单目录。</p>
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
                    busy={busy}
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
    </>
  );
}

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

/** 单行菜单（含递归子项）。 */
function MenuRow({
  item,
  depth,
  allItems,
  onToggle,
  onEdit,
  onAddChild,
  onDelete,
  busy,
}: {
  item: MenuItemDto;
  depth: number;
  allItems: MenuItemDto[];
  onToggle: (m: MenuItemDto) => void;
  onEdit: (m: MenuItemDto) => void;
  onAddChild: (parentId: number) => void;
  onDelete: (m: MenuItemDto) => void;
  busy: boolean;
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
          <button type="button" className="btn btn-ghost btn-sm menu-del" onClick={() => onDelete(item)} disabled={busy}>
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
          busy={busy}
        />
      ))}
    </>
  );
}

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

/** 新增/编辑平台菜单弹窗；编辑时 menuKey 锁定（创建后不可改）。 */
function MenuFormModal({
  mode,
  parentId,
  item,
  allItems,
  onClose,
  onSaved,
}: {
  mode: 'create' | 'edit';
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
    if (mode === 'create' && form.menuKey && !/^[a-z0-9_-]+$/.test(form.menuKey)) {
      setFormError('menuKey 只能包含小写字母/数字/连字符/下划线');
      return;
    }
    setBusy(true);
    try {
      if (mode === 'create') {
        await createPlatformMenu({
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
        // menuKey 创建后不可改，编辑时不提交
        await updatePlatformMenu(item.id, {
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
          标识 menuKey{mode === 'create' ? '（可选，留空由系统从名称派生）' : '（创建后不可修改）'}
          <input
            value={form.menuKey}
            onChange={(e) => setForm({...form, menuKey: e.target.value})}
            maxLength={64}
            placeholder="小写字母/数字/连字符/下划线"
            disabled={mode === 'edit'}
          />
          <span className="field-hint">spoke 侧稳定标识，全局唯一。</span>
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
            启用（平台总开关，关闭后所有组织不可见）
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

// ============ 功能开关 tab：CRUD ============

function PlatformFlagsTab() {
  const [flags, setFlags] = useState<FeatureFlagDto[] | null>(null);
  const [error, setError] = useState('');
  const [modal, setModal] = useState<null | {mode: 'create'} | {mode: 'edit'; item: FeatureFlagDto}>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    try {
      setFlags(await listPlatformFlags());
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载功能开关目录失败');
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const onToggle = async (f: FeatureFlagDto) => {
    try {
      await updatePlatformFlag(f.id, {
        label: f.label,
        description: f.description || undefined,
        sortOrder: f.sortOrder,
        enabled: !f.enabled,
      });
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : '切换失败');
    }
  };

  const onDelete = async (f: FeatureFlagDto) => {
    if (!window.confirm(`确定删除功能开关「${f.label}」？`)) return;
    setBusy(true);
    try {
      await deletePlatformFlag(f.id);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败');
    } finally {
      setBusy(false);
    }
  };

  if (error && !flags) {
    return <div className="form-error">{error}</div>;
  }

  return (
    <>
      {error && <div className="form-error">{error}</div>}
      {flags == null && !error && <div className="empty-hint">加载中…</div>}

      {flags != null && (
        <div className="card">
          <div className="page-head" style={{marginBottom: 8}}>
            <span className="muted">共 {flags.length} 项</span>
            <div className="page-head-right">
              <button type="button" className="btn btn-primary" onClick={() => setModal({mode: 'create'})}>
                ＋ 新增开关
              </button>
            </div>
          </div>
          {flags.length === 0 ? (
            <div className="empty-state">
              <h3>功能开关目录为空</h3>
              <p>点击右上角「新增开关」创建一个平台级功能开关。</p>
            </div>
          ) : (
            <table className="data-table">
              <thead>
                <tr>
                  <th>标识</th>
                  <th>名称</th>
                  <th>描述</th>
                  <th>状态</th>
                  <th>排序</th>
                  <th className="col-actions">操作</th>
                </tr>
              </thead>
              <tbody>
                {flags.map((f) => (
                  <tr key={f.id}>
                    <td>
                      <code className="muted menu-key">{f.flagKey}</code>
                    </td>
                    <td>{f.label}</td>
                    <td className="cell-ellipsis">{f.description || '—'}</td>
                    <td>
                      <button
                        type="button"
                        className={f.enabled ? 'menu-toggle on' : 'menu-toggle off'}
                        onClick={() => onToggle(f)}
                        title={f.enabled ? '点击停用' : '点击启用'}
                      >
                        {f.enabled ? '启用' : '停用'}
                      </button>
                    </td>
                    <td>{f.sortOrder}</td>
                    <td className="col-actions">
                      <button
                        type="button"
                        className="btn btn-ghost btn-sm"
                        onClick={() => setModal({mode: 'edit', item: f})}
                      >
                        编辑
                      </button>
                      <button
                        type="button"
                        className="btn btn-ghost btn-sm menu-del"
                        onClick={() => onDelete(f)}
                        disabled={busy}
                      >
                        删除
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {modal && (
        <FlagFormModal
          mode={modal.mode}
          item={modal.mode === 'edit' ? modal.item : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null);
            void reload();
          }}
        />
      )}
    </>
  );
}

/** 开关表单字段（新增/编辑共用）。 */
interface FlagForm {
  flagKey: string;
  label: string;
  description: string;
  sortOrder: number;
  enabled: boolean;
}

const EMPTY_FLAG_FORM: FlagForm = {
  flagKey: '',
  label: '',
  description: '',
  sortOrder: 0,
  enabled: true,
};

/** 新增/编辑平台功能开关弹窗；编辑时 flagKey 锁定（创建后不可改）。 */
function FlagFormModal({
  mode,
  item,
  onClose,
  onSaved,
}: {
  mode: 'create' | 'edit';
  item: FeatureFlagDto | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form, setForm] = useState<FlagForm>(() =>
    item
      ? {
          flagKey: item.flagKey,
          label: item.label,
          description: item.description,
          sortOrder: item.sortOrder,
          enabled: item.enabled,
        }
      : EMPTY_FLAG_FORM,
  );
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    if (!form.label.trim()) {
      setFormError('开关名称不能为空');
      return;
    }
    if (mode === 'create' && form.flagKey && !/^[a-z0-9_-]+$/.test(form.flagKey)) {
      setFormError('flagKey 只能包含小写字母/数字/连字符/下划线');
      return;
    }
    setBusy(true);
    try {
      if (mode === 'create') {
        await createPlatformFlag({
          flagKey: form.flagKey.trim() || undefined,
          label: form.label.trim(),
          description: form.description.trim() || undefined,
          sortOrder: form.sortOrder,
          enabled: form.enabled,
        });
      } else if (item) {
        // flagKey 创建后不可改，编辑时不提交
        await updatePlatformFlag(item.id, {
          label: form.label.trim(),
          description: form.description.trim() || undefined,
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
    <Modal title={mode === 'create' ? '新增功能开关' : '编辑功能开关'} onClose={onClose}>
      <form className="modal-form" onSubmit={submit}>
        <label>
          开关名称
          <input
            value={form.label}
            onChange={(e) => setForm({...form, label: e.target.value})}
            maxLength={128}
            autoFocus
            required
          />
        </label>
        <label>
          标识 flagKey{mode === 'create' ? '（可选，留空由系统从名称派生）' : '（创建后不可修改）'}
          <input
            value={form.flagKey}
            onChange={(e) => setForm({...form, flagKey: e.target.value})}
            maxLength={64}
            placeholder="小写字母/数字/连字符/下划线"
            disabled={mode === 'edit'}
          />
          <span className="field-hint">spoke 侧稳定标识，全局唯一。</span>
        </label>
        <label>
          描述
          <textarea
            value={form.description}
            onChange={(e) => setForm({...form, description: e.target.value})}
            maxLength={512}
            rows={2}
            placeholder="开关用途说明（可选）"
          />
        </label>
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
            启用（平台总开关，关闭后所有组织不可用）
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

// ============ 工具 tab：只读清单 + 平台总开关 ============

function PlatformToolsTab() {
  const [tools, setTools] = useState<PlatformToolDto[] | null>(null);
  const [error, setError] = useState('');

  const reload = useCallback(async () => {
    try {
      setTools(await listPlatformTools());
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载工具目录失败');
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const onToggle = async (t: PlatformToolDto) => {
    try {
      await setPlatformToolEnabled(t.id, !t.enabled);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : '切换失败');
    }
  };

  if (error && !tools) {
    return <div className="form-error">{error}</div>;
  }

  return (
    <>
      {error && <div className="form-error">{error}</div>}
      {tools == null && !error && <div className="empty-hint">加载中…</div>}

      {tools != null && (
        <div className="card">
          <div className="page-head" style={{marginBottom: 8}}>
            <span className="muted">共 {tools.length} 项（工具清单由系统内置，不可增删）</span>
          </div>
          {tools.length === 0 ? (
            <div className="empty-state">
              <h3>工具目录为空</h3>
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
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                {tools.map((t) => (
                  <tr key={t.id} className={t.enabled ? '' : 'menu-row-disabled'}>
                    <td>{t.displayName}</td>
                    <td>
                      <code className="muted menu-key">{t.toolKey}</code>
                    </td>
                    <td>
                      <span className="badge">{t.toolGroup}</span>
                    </td>
                    <td className="cell-ellipsis">{t.description || '—'}</td>
                    <td>
                      <button
                        type="button"
                        className={t.enabled ? 'menu-toggle on' : 'menu-toggle off'}
                        onClick={() => onToggle(t)}
                        title={t.enabled ? '点击停用' : '点击启用'}
                      >
                        {t.enabled ? '启用' : '停用'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </>
  );
}

// ============ 运维服务器 tab：组织/项目归属 + 用户时效授权 ============

const formatGrantTime = (iso: string) => new Date(iso).toLocaleString();

/** 运维服务器表单字段（新增/编辑共用）；orgId 必填（组织必须），projectId=0 表示不限定项目。
 *  password 仅新增/改密时填写，编辑留空 = 保持原密码（服务端 null 语义）；该字段不回显明文。 */
interface OpsServerForm {
  serverKey: string;
  name: string;
  host: string;
  port: number;
  username: string;
  description: string;
  category: string;
  osType: string;
  sortOrder: number;
  enabled: boolean;
  orgId: number;
  projectId: number;
  password: string;
}

const emptyOpsServerForm = (): OpsServerForm => ({
  serverKey: '',
  name: '',
  host: '',
  port: 22,
  username: '',
  description: '',
  category: '',
  osType: '',
  sortOrder: 0,
  enabled: true,
  orgId: 0,
  projectId: 0,
  password: '',
});

/** 运维服务器目录：归属组织/项目后按归属下发给 spoke；未归属（orgId=0）不下发，须在此补全。 */
function PlatformOpsServersTab() {
  const [servers, setServers] = useState<OpsServerDto[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [modal, setModal] = useState<null | {mode: 'create'} | {mode: 'edit'; item: OpsServerDto}>(null);
  const [grantsFor, setGrantsFor] = useState<OpsServerDto | null>(null);
  const [logsFor, setLogsFor] = useState<OpsServerDto | null>(null);
  // 归属名称映射（表格展示名称而非裸 id）：组织一次拉全量；项目按组织逐个拉，失败降级为空
  const [orgNames, setOrgNames] = useState<Map<number, string>>(new Map());
  const [projectNames, setProjectNames] = useState<Map<number, string>>(new Map());

  const reload = useCallback(async () => {
    try {
      const list = await listOpsServers();
      setServers(list);
      setError('');
      const orgIds = [...new Set(list.map((s) => s.orgId).filter((id) => id > 0))];
      const [orgOpts, projectLists] = await Promise.all([
        listOrgOptions().catch(() => [] as OrgOptionDto[]),
        Promise.all(orgIds.map((orgId) => listProjects(orgId).catch(() => [] as ProjectDto[]))),
      ]);
      setOrgNames(new Map(orgOpts.map((o) => [o.id, o.name])));
      const pMap = new Map<number, string>();
      for (const projects of projectLists) {
        for (const p of projects) pMap.set(p.id, p.name);
      }
      setProjectNames(pMap);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载运维服务器目录失败');
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const onToggle = async (s: OpsServerDto) => {
    setBusy(true);
    setError('');
    try {
      await updateOpsServer(s.id, {enabled: !s.enabled});
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败');
    } finally {
      setBusy(false);
    }
  };

  const onDelete = async (s: OpsServerDto) => {
    if (!window.confirm(`确定删除运维服务器「${s.name}」？该操作不可恢复。`)) return;
    setBusy(true);
    setError('');
    try {
      await deleteOpsServer(s.id);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {error && <div className="form-error">{error}</div>}
      {servers == null && !error && <div className="empty-hint">加载中…</div>}

      {servers != null && (
        <div className="card">
          <div className="page-head" style={{marginBottom: 8}}>
            <span className="muted">共 {servers.length} 台</span>
            <div className="page-head-right">
              <button type="button" className="btn btn-primary" onClick={() => setModal({mode: 'create'})}>
                ＋ 新增服务器
              </button>
            </div>
          </div>
          {servers.length === 0 ? (
            <div className="empty-state">
              <h3>暂无运维服务器</h3>
              <p>新增服务器并归属组织/项目后，spoke 侧即可看到。</p>
            </div>
          ) : (
            <table className="data-table">
              <thead>
                <tr>
                  <th>服务器</th>
                  <th>主机</th>
                  <th>分类</th>
                  <th>系统类型</th>
                  <th>登录用户</th>
                  <th>密码</th>
                  <th>组织 / 项目</th>
                  <th>排序</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {servers.map((s) => (
                  <tr key={s.id} className={s.enabled ? '' : 'menu-row-disabled'}>
                    <td>
                      <div className="menu-label">
                        <span>{s.name}</span>
                      </div>
                      <code className="muted menu-key">{s.serverKey}</code>
                    </td>
                    <td style={{whiteSpace: 'nowrap'}}>
                      {s.host}:{s.port}
                    </td>
                    <td>
                      {s.category ? (
                        <span className="badge badge-current">{s.category}</span>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                    <td style={{whiteSpace: 'nowrap'}}>{s.osType || '—'}</td>
                    <td>{s.username || '—'}</td>
                    <td>
                      {s.passwordSet ? (
                        <span className="badge badge-current" title="已设密码，随目录下发 spoke 可一键连接">已设</span>
                      ) : (
                        <span className="muted">未设</span>
                      )}
                    </td>
                    <td>
                      {s.orgId > 0 ? (
                        <>
                          {orgNames.get(s.orgId) ?? `#${s.orgId}`}
                          {s.projectId > 0 && <> / {projectNames.get(s.projectId) ?? `#${s.projectId}`}</>}
                        </>
                      ) : (
                        <span className="badge badge-archived">未归属</span>
                      )}
                    </td>
                    <td>{s.sortOrder}</td>
                    <td>
                      <button
                        type="button"
                        className={s.enabled ? 'menu-toggle on' : 'menu-toggle off'}
                        onClick={() => onToggle(s)}
                        disabled={busy}
                        title={s.enabled ? '点击停用' : '点击启用'}
                      >
                        {s.enabled ? '启用' : '停用'}
                      </button>
                    </td>
                    <td className="col-actions">
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => setLogsFor(s)}>
                        命令记录
                      </button>
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => setGrantsFor(s)}>
                        授权
                      </button>
                      <button
                        type="button"
                        className="btn btn-ghost btn-sm"
                        onClick={() => setModal({mode: 'edit', item: s})}
                      >
                        编辑
                      </button>
                      <button
                        type="button"
                        className="btn btn-ghost btn-sm menu-del"
                        onClick={() => onDelete(s)}
                        disabled={busy}
                      >
                        删除
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {modal && (
        <OpsServerFormModal
          mode={modal.mode}
          item={modal.mode === 'edit' ? modal.item : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null);
            void reload();
          }}
        />
      )}
      {grantsFor && (
        <OpsServerGrantsModal
          server={grantsFor}
          onClose={() => {
            setGrantsFor(null);
            void reload();
          }}
        />
      )}
      {logsFor && <OpsCommandLogsModal server={logsFor} onClose={() => setLogsFor(null)} />}
    </>
  );
}

/** 新增/编辑运维服务器弹窗：组织下拉 → 联动项目下拉（仅 active）；编辑回显归属。 */
function OpsServerFormModal({
  mode,
  item,
  onClose,
  onSaved,
}: {
  mode: 'create' | 'edit';
  item: OpsServerDto | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form, setForm] = useState<OpsServerForm>(() =>
    item
      ? {
          serverKey: item.serverKey,
          name: item.name,
          host: item.host,
          port: item.port,
          username: item.username,
          description: item.description,
          category: item.category,
          osType: item.osType,
          sortOrder: item.sortOrder,
          enabled: item.enabled,
          orgId: item.orgId,
          projectId: item.projectId,
          password: '',
        }
      : emptyOpsServerForm(),
  );
  const [orgOptions, setOrgOptions] = useState<OrgOptionDto[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [projectsLoading, setProjectsLoading] = useState(false);
  const [projectsError, setProjectsError] = useState('');
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  // 组织下拉一次拉全量；失败降级为空（仍可保存其余字段，归属可后补）
  useEffect(() => {
    listOrgOptions()
      .then(setOrgOptions)
      .catch(() => setOrgOptions([]));
  }, []);

  const loadProjects = useCallback(async (orgId: number, keepProjectId = 0) => {
    setProjects([]);
    setProjectsError('');
    if (orgId <= 0) return;
    setProjectsLoading(true);
    try {
      const list = await listProjects(orgId);
      // 仅 active 项目可选；编辑回显时当前项目即使已归档也要保留在选项里，否则 select 显示不出来
      setProjects(list.filter((p) => p.status === 'active' || p.id === keepProjectId));
    } catch (err) {
      // platformAdmin 可能不是该组织成员（后端 403）：降级为空列表并提示，不阻塞表单
      setProjectsError(err instanceof Error ? err.message : '项目列表加载失败');
    } finally {
      setProjectsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (item && item.orgId > 0) void loadProjects(item.orgId, item.projectId);
  }, [item, loadProjects]);

  const setField = <K extends keyof OpsServerForm>(key: K, value: OpsServerForm[K]) =>
    setForm((f) => ({...f, [key]: value}));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    const name = form.name.trim();
    const host = form.host.trim();
    if (!name) {
      setFormError('请填写名称');
      return;
    }
    if (!host) {
      setFormError('请填写主机地址');
      return;
    }
    if (!Number.isInteger(form.port) || form.port < 1 || form.port > 65535) {
      setFormError('端口需为 1-65535 的整数');
      return;
    }
    const serverKey = form.serverKey.trim();
    if (mode === 'create' && serverKey && !/^[a-z0-9_-]+$/.test(serverKey)) {
      setFormError('服务器标识仅允许小写字母、数字、下划线与连字符');
      return;
    }
    if (form.orgId <= 0) {
      setFormError('请选择归属组织');
      return;
    }
    if (!form.category.trim()) {
      setFormError('请填写分类/标签');
      return;
    }
    setBusy(true);
    try {
      if (mode === 'create') {
        await createOpsServer({
          serverKey: serverKey || undefined,
          name,
          host,
          port: form.port,
          username: form.username.trim() || undefined,
          description: form.description.trim() || undefined,
          category: form.category.trim(),
          osType: form.osType.trim(),
          sortOrder: form.sortOrder,
          enabled: form.enabled,
          orgId: form.orgId,
          projectId: form.projectId,
          password: form.password || undefined,
        });
      } else if (item) {
        await updateOpsServer(item.id, {
          name,
          host,
          port: form.port,
          username: form.username.trim(),
          description: form.description.trim(),
          category: form.category.trim(),
          osType: form.osType.trim(),
          sortOrder: form.sortOrder,
          enabled: form.enabled,
          orgId: form.orgId,
          projectId: form.projectId,
          // 编辑留空 = 不传该字段 = 保持原密码（服务端 null 语义）；填了才覆盖
          password: form.password || undefined,
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
    <Modal title={mode === 'create' ? '新增运维服务器' : '编辑运维服务器'} onClose={onClose}>
      <form className="modal-form" onSubmit={(e) => void submit(e)}>
        <label>
          名称 *
          <input
            value={form.name}
            onChange={(e) => setField('name', e.target.value)}
            placeholder="如：生产环境网关机"
            autoFocus
          />
        </label>
        <label>
          服务器标识
          {mode === 'create' ? (
            <input
              value={form.serverKey}
              onChange={(e) => setField('serverKey', e.target.value)}
              placeholder="留空则按名称自动生成；仅小写字母/数字/_/-，创建后不可改"
            />
          ) : (
            <input value={form.serverKey} disabled />
          )}
        </label>
        <label>
          主机地址 *
          <input value={form.host} onChange={(e) => setField('host', e.target.value)} placeholder="IP 或域名" />
        </label>
        <label>
          端口
          <input
            type="number"
            min={1}
            max={65535}
            value={form.port}
            onChange={(e) => setField('port', Number(e.target.value) || 0)}
          />
        </label>
        <label>
          登录用户
          <input
            value={form.username}
            onChange={(e) => setField('username', e.target.value)}
            placeholder="SSH 登录用户名"
          />
        </label>
        <label>
          登录密码
          <input
            type="password"
            value={form.password}
            onChange={(e) => setField('password', e.target.value)}
            placeholder={
              item?.passwordSet
                ? '已设置密码（留空保持不变；重填则覆盖）'
                : mode === 'create'
                  ? '可选：随目录下发 spoke，spoke 侧可一键连接'
                  : '可选：设置后 spoke 侧可一键连接'
            }
            autoComplete="new-password"
          />
          <span className="field-hint">
            {item?.passwordSet
              ? '密码加密保存于 hub，随目录解密下发 spoke；平台清单不回显明文。'
              : '密码加密保存于 hub，随目录解密下发 spoke；留空则 spoke 侧需录入凭证连接。'}
          </span>
        </label>
        <label>
          描述
          <textarea value={form.description} onChange={(e) => setField('description', e.target.value)} rows={2} />
        </label>
        <label>
          分类/标签 *
          <input
            value={form.category}
            onChange={(e) => setField('category', e.target.value)}
            placeholder="如：数据库 / 网关 / 应用"
          />
          <span className="field-hint">创建必填；用于按分类维护与筛选服务器。</span>
        </label>
        <label>
          系统类型
          <input
            value={form.osType}
            onChange={(e) => setField('osType', e.target.value)}
            maxLength={64}
            placeholder="CentOS 7.9 / Ubuntu 22.04"
          />
          <span className="field-hint">选填；随目录下发 spoke，供 AI 识别目标环境。</span>
        </label>
        <label>
          归属组织
          <select
            value={form.orgId}
            onChange={(e) => {
              const orgId = Number(e.target.value);
              setForm((f) => ({...f, orgId, projectId: 0}));
              void loadProjects(orgId);
            }}
          >
            <option value={0}>— 请选择归属组织（必填）—</option>
            {orgOptions.map((o) => (
              <option key={o.id} value={o.id}>
                {o.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          归属项目
          <select
            value={form.projectId}
            disabled={form.orgId <= 0 || projectsLoading}
            onChange={(e) => setField('projectId', Number(e.target.value))}
          >
            <option value={0}>不限定项目</option>
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
          {projectsLoading && <span className="field-hint">项目加载中…</span>}
          {projectsError && <span className="field-hint">项目列表加载失败：{projectsError}</span>}
          {form.orgId > 0 && !projectsLoading && !projectsError && projects.length === 0 && (
            <span className="field-hint">该组织下暂无可选项目</span>
          )}
        </label>
        <label>
          排序
          <input
            type="number"
            value={form.sortOrder}
            onChange={(e) => setField('sortOrder', Number(e.target.value) || 0)}
          />
        </label>
        <label className="menu-enabled-check">
          <input type="checkbox" checked={form.enabled} onChange={(e) => setField('enabled', e.target.checked)} />
          启用（停用后不下发给 spoke）
        </label>
        {formError && <div className="form-error">{formError}</div>}
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
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

/** 用户时效授权管理弹窗：清单 + 新增/续期 + 撤销。 */
function OpsServerGrantsModal({server, onClose}: {server: OpsServerDto; onClose: () => void}) {
  const [grants, setGrants] = useState<OpsServerGrantDto[] | null>(null);
  const [users, setUsers] = useState<UserDto[]>([]);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const [userId, setUserId] = useState('');
  const [validUntil, setValidUntil] = useState('');
  const [busy, setBusy] = useState(false);
  const [revokingId, setRevokingId] = useState<number | null>(null);

  const reload = useCallback(async () => {
    try {
      setGrants(await listOpsServerGrants(server.id));
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载授权清单失败');
    }
  }, [server.id]);

  useEffect(() => {
    void reload();
    // 用户下拉：platformAdmin 全量用户清单；失败降级为空（提交时服务端仍会校验）
    adminListUsers()
      .then(setUsers)
      .catch(() => setUsers([]));
  }, [reload]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    if (!userId) {
      setFormError('请选择用户');
      return;
    }
    if (!validUntil) {
      setFormError('请选择有效期至');
      return;
    }
    setBusy(true);
    try {
      // datetime-local 值按本地时区解析，转 ISO-8601 提交
      await createOpsServerGrant(server.id, Number(userId), new Date(validUntil).toISOString());
      setUserId('');
      setValidUntil('');
      await reload();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '授权失败');
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (g: OpsServerGrantDto) => {
    if (!window.confirm(`确定撤销用户 ${g.userName} 对「${server.name}」的授权？`)) return;
    setRevokingId(g.id);
    setError('');
    try {
      await deleteOpsGrant(g.id);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : '撤销失败');
    } finally {
      setRevokingId(null);
    }
  };

  return (
    <Modal
      title={`授权管理 · ${server.name}`}
      subtitle={`${server.serverKey} · ${server.host}:${server.port}`}
      onClose={onClose}
      width={680}
    >
      {error && <div className="form-error">{error}</div>}
      {grants == null && !error && <div className="empty-hint">加载中…</div>}
      {grants != null &&
        (grants.length === 0 ? (
          <div className="empty-hint">暂无授权记录</div>
        ) : (
          <table className="data-table" style={{marginBottom: 16}}>
            <thead>
              <tr>
                <th>用户</th>
                <th>有效期</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {grants.map((g) => (
                <tr key={g.id} className={g.expired ? 'menu-row-disabled' : ''}>
                  <td>{g.userName}</td>
                  <td style={{whiteSpace: 'nowrap'}}>
                    {formatGrantTime(g.validFrom)} ~ {formatGrantTime(g.validUntil)}
                  </td>
                  <td>
                    <span className={g.expired ? 'badge audit-fail' : 'badge audit-ok'}>
                      {g.expired ? '已过期' : '有效'}
                    </span>
                  </td>
                  <td className="col-actions">
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm menu-del"
                      onClick={() => void revoke(g)}
                      disabled={busy || revokingId === g.id}
                    >
                      撤销
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}

      <form className="modal-form" onSubmit={(e) => void submit(e)}>
        <label>
          用户
          <select value={userId} onChange={(e) => setUserId(e.target.value)}>
            <option value="">选择用户…</option>
            {users.map((u) => (
              <option key={u.id} value={u.id}>
                {u.displayName ? `${u.displayName}（${u.username}）` : u.username}
              </option>
            ))}
          </select>
        </label>
        <label>
          有效期至 *
          <input type="datetime-local" value={validUntil} onChange={(e) => setValidUntil(e.target.value)} />
          <span className="field-hint">同一用户重复提交=续期（更新有效期）</span>
        </label>
        {formError && <div className="form-error">{formError}</div>}
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            关闭
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? '提交中…' : '授权'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** 命令执行记录弹窗：按执行时间倒序分页（Spring Page 形状），AI/用户来源徽标区分。 */
function OpsCommandLogsModal({server, onClose}: {server: OpsServerDto; onClose: () => void}) {
  const PAGE_SIZE = 50;
  const [page, setPage] = useState(0);
  const [data, setData] = useState<OpsCommandLogPage | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const load = useCallback(async (p: number) => {
    setLoading(true);
    setError('');
    try {
      const result = await listOpsServerCommandLogs(server.id, p, PAGE_SIZE);
      setData(result);
      setPage(p);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载命令记录失败');
    } finally {
      setLoading(false);
    }
  }, [server.id]);

  useEffect(() => {
    void load(0);
  }, [load]);

  const totalPages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <Modal title={`命令记录 — ${server.name}`} subtitle={server.serverKey} onClose={onClose} width={720}>
      {error && <div className="form-error">{error}</div>}
      {loading && <div className="empty-hint">加载中…</div>}
      {!loading && !error && data && data.items.length === 0 && (
        <div className="empty-hint">暂无命令执行记录</div>
      )}
      {!loading && !error && data && data.items.length > 0 && (
        <>
          <table className="data-table">
            <thead>
              <tr>
                <th>时间</th>
                <th>操作人</th>
                <th>类型</th>
                <th>命令</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((log) => (
                <tr key={log.id}>
                  <td style={{whiteSpace: 'nowrap'}}>{new Date(log.executedAt).toLocaleString()}</td>
                  <td>{log.operator || '—'}</td>
                  <td>
                    {log.source === 'ai' ? (
                      <span className="badge audit-ok">AI</span>
                    ) : (
                      <span className="badge audit-fail">用户</span>
                    )}
                  </td>
                  <td>
                    <code className="audit-action">{log.command}</code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="page-head" style={{marginTop: 8}}>
            <span className="muted">
              第 {data.page + 1} / {totalPages} 页 · 共 {data.total} 条
            </span>
            <div className="page-head-right">
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => void load(page - 1)}
                disabled={page <= 0 || loading}
              >
                ← 上一页
              </button>
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => void load(page + 1)}
                disabled={page + 1 >= totalPages || loading}
              >
                下一页 →
              </button>
            </div>
          </div>
        </>
      )}
    </Modal>
  );
}
