import {useCallback, useEffect, useState} from 'react';
import {
  adminCreateUser,
  adminDeleteUser,
  adminListUsers,
  adminResetUserPassword,
  listOrgOptions,
} from '../api';
import {loadSession} from '../auth';
import Modal from '../components/Modal';
import type {CreatedUserDto, OrgOptionDto, UserDto} from '../types';

const STATUS_LABELS: Record<string, string> = {
  active: '正常',
  disabled: '禁用',
};

const formatDate = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString() : '—');

/**
 * 平台管理员用户管理（仅 platformAdmin 可见/可调通，服务端强制校验）。
 * 开通用户 / 重置密码：服务端随机生成一次性密码，<b>仅在当次响应中明文展示一次</b>（不再投递邮箱），
 * 由管理员线下交付；用户首登 / 重置后首登强制改密，密码 60 天到期。
 */
export default function UsersPage() {
  const [users, setUsers] = useState<UserDto[] | null>(null);
  const [loadError, setLoadError] = useState('');
  // 创建弹窗：form 视图 / 成功视图（created 非空时展示一次性密码）
  const [createOpen, setCreateOpen] = useState(false);
  const [created, setCreated] = useState<CreatedUserDto | null>(null);
  // 一次性密码弹窗的来源场景：创建用户（初始密码）/ 重置密码（新密码）。
  // email 在创建接口为必填，不能用 created.email 是否为空推断场景，必须显式标记。
  const [createdMode, setCreatedMode] = useState<'create' | 'reset'>('create');
  // 一次性密码复制
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState('');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  // 初始组织（可选）：给了则创建即入组；orgRole 仅在选了组织时生效
  const [orgId, setOrgId] = useState('');
  const [orgRole, setOrgRole] = useState('member');
  const [orgOptions, setOrgOptions] = useState<OrgOptionDto[]>([]);
  const [formError, setFormError] = useState('');
  const [pageError, setPageError] = useState('');
  const [busy, setBusy] = useState(false);
  const [resettingId, setResettingId] = useState<number | null>(null);
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

  const resetCopyState = () => {
    setCopied(false);
    setCopyError('');
  };

  const openCreate = () => {
    setUsername('');
    setEmail('');
    setDisplayName('');
    setOrgId('');
    setOrgRole('member');
    setFormError('');
    setCreated(null);
    setCreatedMode('create');
    resetCopyState();
    setCreateOpen(true);
    // 打开弹窗时拉全量组织选项（仅 platformAdmin 可调通，失败则降级为空列表）
    listOrgOptions()
      .then(setOrgOptions)
      .catch(() => setOrgOptions([]));
  };

  const closeCreate = async () => {
    setCreateOpen(false);
    setCreated(null);
    await reload();
  };

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
      // 成功后切到「创建成功」视图，一次性明文密码仅此一次展示
      setCreated(u);
      setCreatedMode('create');
      resetCopyState();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '创建失败');
    } finally {
      setBusy(false);
    }
  };

  const resetPassword = async (u: UserDto) => {
    if (
      !window.confirm(
        `确定重置用户 ${u.username} 的密码？重置后其当前各端登录立即失效，新密码需线下交付，且下次登录强制改密。`,
      )
    ) {
      return;
    }
    setPageError('');
    setResettingId(u.id);
    try {
      const result = await adminResetUserPassword(u.id);
      setCreated(result);
      setCreatedMode('reset');
      resetCopyState();
      setCreateOpen(true);
      await reload();
    } catch (err) {
      setPageError(err instanceof Error ? err.message : '重置失败');
    } finally {
      setResettingId(null);
    }
  };

  const copyTempPassword = async (tempPassword: string) => {
    setCopyError('');
    try {
      await navigator.clipboard.writeText(tempPassword);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopyError('复制失败，请手动选中密码复制');
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
          <p className="page-desc">
            平台用户仅由管理员开通；初始/重置密码仅展示一次，请线下交付，首登强制改密，密码 60 天到期。
          </p>
        </div>
        <div className="page-head-right">
          <button type="button" className="btn btn-primary" onClick={openCreate}>
            ＋ 添加用户
          </button>
        </div>
      </div>

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
                <th>密码到期</th>
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
                    {formatDate(u.passwordExpiresAt)}
                    {u.passwordExpiringSoon && u.status === 'active' && (
                      <span className="badge badge-archived" title="距到期不足 7 天，请提醒用户尽快改密">
                        临期
                      </span>
                    )}
                  </td>
                  <td>
                    {u.platformAdmin && <span className="badge role-admin">平台管理员</span>}
                    {u.mustChangePassword && <span className="badge role-owner">待改密</span>}
                  </td>
                  <td style={{whiteSpace: 'nowrap'}}>
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      disabled={u.status !== 'active' || resettingId === u.id}
                      title={u.status !== 'active' ? '账号已禁用，无法重置密码' : undefined}
                      onClick={() => void resetPassword(u)}
                    >
                      {resettingId === u.id ? '重置中…' : '重置密码'}
                    </button>{' '}
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
          title={created ? (createdMode === 'create' ? '用户已开通' : '密码已重置') : '添加用户'}
          subtitle={created ? undefined : '初始密码由系统随机生成，仅在创建成功后显示一次'}
          onClose={() => void closeCreate()}
        >
          {created ? (
            <div>
              <p>
                用户 <strong>{created.username}</strong> 的{createdMode === 'create' ? '初始' : '新'}密码已生成。请立即复制并线下交付，
                {createdMode === 'create'
                  ? '其首次登录将被强制修改密码：'
                  : '重置后其各端登录立即失效，下次登录将被强制修改密码：'}
              </p>
              <div
                style={{
                  fontFamily: "ui-monospace, 'Cascadia Code', Consolas, monospace",
                  wordBreak: 'break-all',
                  padding: '10px 12px',
                  background: 'var(--bg-input)',
                  border: '1px solid var(--border)',
                  borderRadius: 8,
                  margin: '12px 0',
                }}
              >
                {created.tempPassword}
              </div>
              <div className="form-error">此密码仅显示这一次，请立即复制保存；关闭后将无法再次查看。</div>
              {copyError && <div className="form-error">{copyError}</div>}
              <div className="modal-actions">
                <button
                  type="button"
                  className="btn btn-ghost"
                  onClick={() => void copyTempPassword(created.tempPassword)}
                >
                  {copied ? '已复制 ✓' : '复制密码'}
                </button>
                <button type="button" className="btn btn-primary" onClick={() => void closeCreate()}>
                  完成
                </button>
              </div>
            </div>
          ) : (
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
                邮箱
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
                <button type="button" className="btn btn-ghost" onClick={() => void closeCreate()}>
                  取消
                </button>
                <button type="submit" className="btn btn-primary" disabled={busy}>
                  {busy ? '创建中…' : '创建'}
                </button>
              </div>
            </form>
          )}
        </Modal>
      )}
    </div>
  );
}
