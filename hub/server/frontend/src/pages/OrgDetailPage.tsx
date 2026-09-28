import {useCallback, useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {addMember, listMembers, removeMember, updateMemberRole} from '../api';
import Modal from '../components/Modal';
import type {MeResponse, MemberDto} from '../types';

interface Props {
  me: MeResponse;
  /** 成员角色变化可能影响我在该组织的角色，变更后回调 App 重新拉取 me */
  onChanged: () => void;
}

const ROLE_LABELS: Record<string, string> = {
  owner: '所有者',
  admin: '管理员',
  member: '成员',
  guest: '访客',
};

/** 可指派的角色（owner 只能由创建产生，不可指派） */
const ASSIGNABLE_ROLES = ['admin', 'member', 'guest'];

/** 组织详情：成员表格 + 弹窗加成员 + 角色下拉（owner|admin 可见操作区）。 */
export default function OrgDetailPage({me, onChanged}: Props) {
  const navigate = useNavigate();
  const {orgId: orgIdParam} = useParams();
  const orgId = Number(orgIdParam);
  const org = me.orgs.find((o) => o.id === orgId);
  const myRole = org?.role ?? null;
  const canManage = myRole === 'owner' || myRole === 'admin';

  const [members, setMembers] = useState<MemberDto[] | null>(null);
  const [error, setError] = useState('');
  const [addOpen, setAddOpen] = useState(false);
  const [addUser, setAddUser] = useState('');
  const [addRole, setAddRole] = useState('member');
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    try {
      setMembers(await listMembers(orgId));
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载成员失败');
    }
  }, [orgId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const submitAdd = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    setBusy(true);
    try {
      await addMember(orgId, addUser.trim(), addRole);
      setAddOpen(false);
      setAddUser('');
      await reload();
      onChanged();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '添加失败');
    } finally {
      setBusy(false);
    }
  };

  const changeRole = async (m: MemberDto, role: string) => {
    try {
      await updateMemberRole(orgId, m.userId, role);
      await reload();
      // 改的可能是自己（admin 降自己），me 里的角色需校准
      if (m.userId === me.user.id) onChanged();
    } catch (err) {
      alert(err instanceof Error ? err.message : '修改失败');
      await reload();
    }
  };

  const remove = async (m: MemberDto) => {
    if (!window.confirm(`确定将 ${m.displayName || m.username} 移出组织？`)) return;
    try {
      await removeMember(orgId, m.userId);
      await reload();
      if (m.userId === me.user.id) onChanged();
    } catch (err) {
      alert(err instanceof Error ? err.message : '移除失败');
    }
  };

  if (!org) {
    return (
      <div className="page">
        <div className="empty-state">
          <h3>无法查看该组织</h3>
          <p>你不是该组织成员，或组织不存在。</p>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <button type="button" className="btn btn-ghost btn-sm back-btn" onClick={() => navigate('/orgs')}>
            ← 组织列表
          </button>
          <h2>{org.name}</h2>
          <p className="page-desc">
            @{org.slug} · 我的角色：<span className={`badge role-${myRole}`}>{ROLE_LABELS[myRole ?? ''] ?? myRole}</span>
          </p>
        </div>
        {canManage && (
          <div className="page-head-right">
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                setFormError('');
                setAddOpen(true);
              }}
            >
              ＋ 添加成员
            </button>
          </div>
        )}
      </div>

      {error && <div className="form-error">{error}</div>}
      {members == null && !error && <div className="empty-hint">加载中…</div>}

      <div className="card role-perm-card">
        <h3 className="role-perm-title">角色权限说明（只读）</h3>
        <ul className="role-perm-list">
          <li>
            <span className="badge role-owner">所有者 owner</span>
            <span>组织最高权限：管理成员 / AppKey、审计与网关日志、Provider、全部项目与知识库。</span>
          </li>
          <li>
            <span className="badge role-admin">管理员 admin</span>
            <span>除组织级结算外等同所有者：管成员与密钥、读写全部项目与知识库。</span>
          </li>
          <li>
            <span className="badge role-member">成员 member</span>
            <span>可创建项目；可读写团队（team）/公开（public）项目；可管理自己创建的 AppKey；他人私有（private）项目不可见。</span>
          </li>
          <li>
            <span className="badge role-guest">访客 guest</span>
            <span>只读：可查看 team / public 项目与知识库，不能创建或编辑。</span>
          </li>
        </ul>
        <p className="role-perm-note">
          说明：项目可见性分 team（本组织可见，默认）/ public（组织外登录用户可读）/ private（仅创建者与组织 owner、admin 可见）；
          项目编辑权限为组织 owner / admin / 项目创建者 / 非 guest 成员。暂无项目级成员表与自定义角色。
        </p>
      </div>

      {members != null && (
        <div className="card">
          <table className="data-table">
            <thead>
              <tr>
                <th>用户</th>
                <th>昵称</th>
                <th>角色</th>
                <th>加入时间</th>
                {canManage && <th className="col-actions">操作</th>}
              </tr>
            </thead>
            <tbody>
              {members.map((m) => {
                const isOwnerRow = m.role === 'owner';
                const isMe = m.userId === me.user.id;
                return (
                  <tr key={m.userId}>
                    <td>
                      {m.username}
                      {isMe && <span className="badge badge-current">我</span>}
                    </td>
                    <td>{m.displayName || '—'}</td>
                    <td>
                      {canManage && !isOwnerRow ? (
                        <select
                          className="role-select"
                          value={m.role}
                          onChange={(e) => void changeRole(m, e.target.value)}
                        >
                          {ASSIGNABLE_ROLES.map((r) => (
                            <option key={r} value={r}>
                              {ROLE_LABELS[r] ?? r}
                            </option>
                          ))}
                        </select>
                      ) : (
                        <span className={`badge role-${m.role}`}>{ROLE_LABELS[m.role] ?? m.role}</span>
                      )}
                    </td>
                    <td>{m.joinedAt ? new Date(m.joinedAt).toLocaleDateString() : '—'}</td>
                    {canManage && (
                      <td className="col-actions">
                        {!isOwnerRow && (
                          <button type="button" className="btn btn-danger btn-sm" onClick={() => void remove(m)}>
                            移除
                          </button>
                        )}
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {addOpen && (
        <Modal title="添加成员" subtitle={`加入组织：${org.name}`} onClose={() => setAddOpen(false)}>
          <form className="modal-form" onSubmit={(e) => void submitAdd(e)}>
            <label>
              用户名或邮箱
              <input value={addUser} onChange={(e) => setAddUser(e.target.value)} autoFocus required />
            </label>
            <label>
              角色
              <select value={addRole} onChange={(e) => setAddRole(e.target.value)}>
                {ASSIGNABLE_ROLES.map((r) => (
                  <option key={r} value={r}>
                    {ROLE_LABELS[r] ?? r}
                  </option>
                ))}
              </select>
            </label>
            {formError && <div className="form-error">{formError}</div>}
            <div className="modal-actions">
              <button type="button" className="btn btn-ghost" onClick={() => setAddOpen(false)}>
                取消
              </button>
              <button type="submit" className="btn btn-primary" disabled={busy}>
                {busy ? '添加中…' : '添加'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
