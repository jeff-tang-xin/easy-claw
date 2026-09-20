import {useCallback, useEffect, useState} from 'react';
import {fetchRoleMatrix} from '../api';
import type {RoleMatrixResponse} from '../types';
import {NAV_GROUPS, PERMISSION_LABELS, ROLE_LABELS} from '../nav';

interface Props {
  /** 当前用户在当前组织的角色；仅 owner/admin 可看（门控 org.manage）。 */
  role: string | null;
}

const YES = <span title="可见 / 拥有">✅</span>;
const NO = <span className="muted" title="不可见 / 无此权限">—</span>;

/**
 * 角色与权限（只读）：展示「菜单 / 权限码 ↔ 角色 ↔ 平台管理员」的关联矩阵。
 * 角色→权限数据来自后端 GET /api/role-matrix（Permissions 为唯一权威）；
 * 菜单→权限来自前端导航单一数据源 nav.ts。本页不做任何编辑，纯展示服务端真实鉴权规则。
 */
export default function RolesPage({role}: Props) {
  const [matrix, setMatrix] = useState<RoleMatrixResponse | null>(null);
  const [error, setError] = useState('');

  const allowed = role === 'owner' || role === 'admin';

  const reload = useCallback(async () => {
    if (!allowed) return;
    try {
      setMatrix(await fetchRoleMatrix());
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载角色权限矩阵失败');
    }
  }, [allowed]);

  useEffect(() => {
    void reload();
  }, [reload]);

  if (!allowed) {
    return (
      <div className="page">
        <div className="empty-state">
          <h3>无权限</h3>
          <p>角色与权限矩阵仅组织 owner / admin 可见。</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="page">
        <div className="page-head">
          <div>
            <h2>角色与权限</h2>
            <p className="page-desc">菜单、角色与权限码的对应关系（只读）。</p>
          </div>
        </div>
        <div className="form-error">{error}</div>
      </div>
    );
  }

  if (!matrix) {
    return (
      <div className="page">
        <div className="empty-hint">加载中…</div>
      </div>
    );
  }

  // role -> 权限集合，便于查表
  const permsByRole = new Map(matrix.roles.map((r) => [r.role, new Set(r.permissions)]));
  const platformPerms = new Set(matrix.platformAdminPerms);
  // 展示列：按后端固定顺序 owner/admin/member/guest，再加「平台管理员」叠加列
  const roles = matrix.roles.map((r) => r.role);

  const roleHas = (r: string, permCandidates: string[]) =>
    permCandidates.some((p) => permsByRole.get(r)?.has(p));

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>角色与权限</h2>
          <p className="page-desc">
            展示每个角色能看到哪些菜单、持有哪些权限码。此为只读视图，规则由服务端统一定义。
          </p>
        </div>
      </div>

      {/* ============ 菜单 × 角色 ============ */}
      <h3 className="roles-section-title">菜单可见性</h3>
      <div className="card">
        <table className="data-table roles-table">
          <thead>
            <tr>
              <th>菜单</th>
              {roles.map((r) => (
                <th key={r} className="roles-col">
                  {ROLE_LABELS[r] ?? r}
                </th>
              ))}
              <th className="roles-col">平台管理员</th>
            </tr>
          </thead>
          <tbody>
            {NAV_GROUPS.map((group) =>
              group.items.map((item, idx) => (
                <tr key={item.to}>
                  <td>
                    {idx === 0 && (
                      <span className="muted roles-group-tag">{group.title} · </span>
                    )}
                    <span>
                      {item.icon} {item.label}
                    </span>
                  </td>
                  {roles.map((r) => (
                    <td key={r} className="roles-cell">
                      {roleHas(r, item.anyOfPerms) ? YES : NO}
                    </td>
                  ))}
                  <td className="roles-cell">
                    {item.anyOfPerms.some((p) => platformPerms.has(p)) ? YES : NO}
                  </td>
                </tr>
              )),
            )}
          </tbody>
        </table>
      </div>
      <p className="field-hint roles-legend">
        ✅ 可见　— 不可见。可见性完全由该菜单所需权限码决定；owner/admin 持有
        provider.manage，故也能看到「平台 · Provider」。
      </p>

      {/* ============ 权限码 × 角色 ============ */}
      <h3 className="roles-section-title">权限码明细</h3>
      <div className="card">
        <table className="data-table roles-table">
          <thead>
            <tr>
              <th>权限码</th>
              {roles.map((r) => (
                <th key={r} className="roles-col">
                  {ROLE_LABELS[r] ?? r}
                </th>
              ))}
              <th className="roles-col">平台管理员</th>
            </tr>
          </thead>
          <tbody>
            {Object.entries(PERMISSION_LABELS).map(([code, label]) => (
              <tr key={code}>
                <td>
                  <div>{label}</div>
                  <code className="muted roles-code">{code}</code>
                </td>
                {roles.map((r) => (
                  <td key={r} className="roles-cell">
                    {permsByRole.get(r)?.has(code) ? YES : NO}
                  </td>
                ))}
                <td className="roles-cell">{platformPerms.has(code) ? YES : NO}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="field-hint roles-legend">
        「平台管理员」不是组织角色，而是用户上的一个标志，在其组织角色权限之上额外叠加
        user.manage / provider.manage 两项平台级权限。
      </p>
    </div>
  );
}
