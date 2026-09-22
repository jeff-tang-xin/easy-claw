import {useCallback, useEffect, useMemo, useState} from 'react';
import {listOrgMenuSettings, listPlatformMenus, setOrgMenuVisible} from '../api';
import type {MenuItemDto, OrgMenuSettingDto} from '../types';

interface Props {
  /** 当前组织 id；为 null 时显示「先创建组织」空态 */
  orgId: number | null;
}

/**
 * 组织菜单可见性：平台目录只读展示（树形），组织侧仅可切换「是否显示」。
 * 未设置过的菜单默认可见；平台停用的菜单不可切换（平台总开关优先）。
 */
export default function MenuConfigPage({orgId}: Props) {
  const [items, setItems] = useState<MenuItemDto[] | null>(null);
  const [settings, setSettings] = useState<OrgMenuSettingDto[] | null>(null);
  const [error, setError] = useState('');
  const [busyId, setBusyId] = useState<number | null>(null);

  const reload = useCallback(async () => {
    if (orgId == null) return;
    try {
      const [menus, orgSettings] = await Promise.all([listPlatformMenus(), listOrgMenuSettings(orgId)]);
      setItems(menus);
      setSettings(orgSettings);
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载菜单目录失败');
    }
  }, [orgId]);

  useEffect(() => {
    setItems(null);
    setSettings(null);
    void reload();
  }, [reload]);

  /** menuId → 该组织生效态；无行 = 默认可见 */
  const visibleById = useMemo(() => {
    const map = new Map<number, boolean>();
    for (const s of settings ?? []) map.set(s.menuId, s.visible);
    return map;
  }, [settings]);

  const tree = useMemo(() => (items ? buildMenuTree(items) : []), [items]);

  const onToggle = async (m: MenuItemDto) => {
    if (orgId == null || !m.enabled) return;
    const current = visibleById.get(m.id) ?? true;
    setBusyId(m.id);
    try {
      await setOrgMenuVisible(orgId, m.id, !current);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : '切换失败');
    } finally {
      setBusyId(null);
    }
  };

  if (orgId == null) {
    return (
      <div className="page">
        <div className="empty-state">
          <h3>尚未选择组织</h3>
          <p>请先创建或选择一个组织，再配置菜单可见性。</p>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>菜单可见性</h2>
          <p className="page-desc">
            菜单目录由平台统一维护（只读）；这里决定本组织显示哪些菜单。未设置过的菜单默认显示。
          </p>
        </div>
      </div>

      {error && <div className="form-error">{error}</div>}
      {items == null && !error && <div className="empty-hint">加载中…</div>}

      {items != null && (
        <div className="card">
          {items.length === 0 ? (
            <div className="empty-state">
              <h3>平台菜单目录为空</h3>
              <p>平台尚未配置任何菜单，请联系平台管理员。</p>
            </div>
          ) : (
            <table className="data-table menu-table">
              <thead>
                <tr>
                  <th>菜单</th>
                  <th>标识</th>
                  <th>路径</th>
                  <th>平台状态</th>
                  <th>本组织显示</th>
                </tr>
              </thead>
              <tbody>
                {tree.map((m) => (
                  <MenuRow
                    key={m.id}
                    item={m}
                    depth={0}
                    allItems={items}
                    visibleById={visibleById}
                    busyId={busyId}
                    onToggle={onToggle}
                  />
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
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
  visibleById,
  busyId,
  onToggle,
}: {
  item: MenuItemDto;
  depth: number;
  allItems: MenuItemDto[];
  visibleById: Map<number, boolean>;
  busyId: number | null;
  onToggle: (m: MenuItemDto) => void;
}) {
  const children = allItems.filter((x) => x.parentId === item.id);
  const visible = visibleById.get(item.id) ?? true;
  return (
    <>
      <tr className={item.enabled ? '' : 'menu-row-disabled'}>
        <td>
          <div className="menu-label" style={{paddingLeft: depth * 22}}>
            {depth > 0 && <span className="menu-tree-guide">└ </span>}
            {item.icon && <span className="menu-icon">{item.icon}</span>}
            <span>{item.label}</span>
          </div>
        </td>
        <td>
          <code className="muted menu-key">{item.menuKey}</code>
        </td>
        <td className="cell-ellipsis">{item.path || '—'}</td>
        <td>{item.enabled ? <span className="badge">启用</span> : <span className="badge badge-archived">平台停用</span>}</td>
        <td>
          {item.enabled ? (
            <button
              type="button"
              className={visible ? 'menu-toggle on' : 'menu-toggle off'}
              onClick={() => onToggle(item)}
              disabled={busyId === item.id}
              title={visible ? '点击隐藏' : '点击显示'}
            >
              {visible ? '显示' : '隐藏'}
            </button>
          ) : (
            <span className="muted">—</span>
          )}
        </td>
      </tr>
      {children.map((c) => (
        <MenuRow
          key={c.id}
          item={c}
          depth={depth + 1}
          allItems={allItems}
          visibleById={visibleById}
          busyId={busyId}
          onToggle={onToggle}
        />
      ))}
    </>
  );
}
