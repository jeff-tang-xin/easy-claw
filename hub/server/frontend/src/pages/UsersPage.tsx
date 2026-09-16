import {useCallback, useEffect, useState} from 'react';
import {adminCreateUser, adminDeleteUser, adminListUsers, listOrgOptions} from '../api';
import {loadSession} from '../auth';
import Modal from '../components/Modal';
import type {OrgOptionDto, UserDto} from '../types';

const STATUS_LABELS: Record<string, string> = {
  active: '正常',
  disabled: '禁用',
};

/**
 * 平台管理员用户管理（仅 platformAdmin 可见/可调通，服务端强制校验）。
 * 列表 + 弹窗开通用户：临时密码由服务端生成并投递邮箱（A0 无 SMTP，服务端日志兜底），首登强制改密。
 */
export default function UsersPage() {
  const [users, setUsers] = useState<UserDto[] | null>(null);
  const [loadError, setLoadError] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  // 初始组织（可选）：给了则创建即入组；orgRole 仅在选了组织时生效
  const [orgId, setOrgId] = useState('');
  const [orgRole, setOrgRole] = useState('member');
  const [orgOptions, setOrgOptions] = useState<OrgOptionDto[]>([]);
  const [formError, setFormError] = useState('');
  const [createdHint, setCreatedHint] = useState('');
  const [pageError, setPageError] = useState('');
  const [busy, setBusy] = useState(false);
  const currentUserId = loadSession()?.user.id ?? null;

  const reload = useCallback(async () => {
    try {
      setLoadError('');
      setUsers(await adminListUsers());
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : '加载失败');
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const submitCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    setBusy(true);
    try {
      const pickedOrg = orgId === '' ? null : Number(orgId);
      const u = await adminCreateUser(
        username.trim(),
        email.trim(),
        displayName.trim() || null,
        pickedOrg,
        pickedOrg === null ? null : orgRole,
      );
      setCreatedHint(
        `用户 ${u.username} 已开通${pickedOrg !== null ? '并加入所选组织' : ''}：临时密码已投递其邮箱（A0 无 SMTP，见服务端日志兜底输出），首次登录将被强制改密。`,
      );
      setCreateOpen(false);
      setUsername('');
      setEmail('');
      setDisplayName('');
      setOrgId('');
      setOrgRole('member');
      await reload();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '创建失败');
    } finally {
      setBusy(false);
    }
  };

  const removeUser = async (u: UserDto) => {
    if (!window.confirm(`确定删除用户 ${u.username}？删除后不可恢复。`)) return;
    setPageError('');
    try {
      await adminDeleteUser(u.id);
      await reload();
    } catch (err) {
      // 删除自己/平台管理员时服务端返回 403，message 直接展示
      setPageError(err instanceof Error ? err.message : '删除失败');
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>用户管理</h2>
          <p className="page-desc">平台用户仅由管理员开通；新用户凭临时密码首登后强制改密。</p>
        </div>
        <div className="page-head-right">
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => {
              setFormError('');
              setCreateOpen(true);
              // 打开弹窗时拉全量组织选项（仅 platformAdmin 可调通，失败则降级为空列表）
              listOrgOptions()
                .then(setOrgOptions)
                .catch(() => setOrgOptions([]));
            }}
          >
            ＋ 添加用户
          </button>
        </div>
      </div>

      {createdHint && <div className="form-success">{createdHint}</div>}
      {pageError && <div className="form-error">{pageError}</div>}

      {loadError ? (
        <div className="empty-state">
          <h3>加载失败</h3>
          <p>{loadError}</p>
        </div>
      ) : users === null ? (
        <div className="empty-hint">加载中…</div>
      ) : users.length === 0 ? (
        <div className="empty-state">
          <h3>暂无用户</h3>
          <p>点击右上角「添加用户」开通第一个平台用户。</p>
        </div>
      ) : (
        <div className="card">
          <table className="data-table">
            <thead>
              <tr>
                <th>用户名</th>
                <th>邮箱</th>
                <th>昵称</th>
                <th>状态</th>
                <th>标识</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.id}>
                  <td>{u.username}</td>
                  <td>{u.email ?? '—'}</td>
                  <td>{u.displayName ?? '—'}</td>
                  <td>
                    <span className={`badge ${u.status === 'active' ? 'role-member' : 'badge-archived'}`}>
                      {STATUS_LABELS[u.status] ?? u.status}
                    </span>
                  </td>
                  <td>
                    {u.platformAdmin && <span className="badge role-admin">平台管理员</span>}
                    {u.mustChangePassword && <span className="badge role-owner">待改密</span>}
                  </td>
                  <td>
                    <button
                      type="button"
                      className="btn btn-danger btn-sm"
                      disabled={(currentUserId !== null && u.id === currentUserId) || u.platformAdmin}
                      title={
                        currentUserId !== null && u.id === currentUserId
                          ? '不能删除当前登录用户'
                          : u.platformAdmin
                            ? '不能删除平台管理员'
                            : undefined
                      }
                      onClick={() => void removeUser(u)}
                    >
                      删除
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {createOpen && (
        <Modal
          title="添加用户"
          subtitle="临时密码由系统生成并投递邮箱（A0 无 SMTP，见服务端日志兜底）"
          onClose={() => setCreateOpen(false)}
        >
          <form className="modal-form" onSubmit={(e) => void submitCreate(e)}>
            <label>
              用户名
              <input
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                minLength={3}
                maxLength={64}
                autoFocus
                required
              />
            </label>
            <label>
              邮箱<span className="field-hint">接收临时密码</span>
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                maxLength={128}
                required
              />
            </label>
            <label>
              昵称（可选）
              <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={64} />
            </label>
            <label>
              初始组织（可选）<span className="field-hint">选了则创建即入组，也可稍后在组织内添加</span>
              <select value={orgId} onChange={(e) => setOrgId(e.target.value)}>
                <option value="">暂不入组</option>
                {orgOptions.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}（{o.slug}）
                  </option>
                ))}
              </select>
            </label>
            {orgId !== '' && (
              <label>
                组织角色
                <select value={orgRole} onChange={(e) => setOrgRole(e.target.value)}>
                  <option value="member">member（成员）</option>
                  <option value="admin">admin（管理员）</option>
                </select>
              </label>
            )}
            {formError && <div className="form-error">{formError}</div>}
            <div className="modal-actions">
              <button type="button" className="btn btn-ghost" onClick={() => setCreateOpen(false)}>
                取消
              </button>
              <button type="submit" className="btn btn-primary" disabled={busy}>
                {busy ? '创建中…' : '创建'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
