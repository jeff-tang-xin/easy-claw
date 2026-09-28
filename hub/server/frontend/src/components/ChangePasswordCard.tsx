import {useState} from 'react';
import {changePassword, fetchMe, login} from '../api';
import {saveSession} from '../auth';
import type {MeResponse} from '../types';

interface Props {
  /** 改密成功后用新密码重新登录（旧 token 的 mcp 标记仍在，必须换新 token） */
  usernameOrEmail: string;
  /** 登录页刚输过的（临时）密码可作旧密码预填 */
  defaultOldPassword?: string;
  onDone: (me: MeResponse) => void;
}

/**
 * 首登强制改密卡片（mcp 门禁：改密前仅改密/登出/查自身可用）。
 * 成功链路：change-password → 用新密码重新登录拿无 mcp 的 token → fetchMe → onDone。
 * 与 LoginPage 同款居中卡片布局；登录页切步与带 mcp 会话刷新两个场景共用。
 */
export default function ChangePasswordCard({usernameOrEmail, defaultOldPassword = '', onDone}: Props) {
  const [oldPassword, setOldPassword] = useState(defaultOldPassword);
  const [newPassword, setNewPassword] = useState('');
  const [newPassword2, setNewPassword2] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (newPassword.length < 8) {
      setError('新密码至少 8 位');
      return;
    }
    if (newPassword !== newPassword2) {
      setError('两次输入的新密码不一致');
      return;
    }
    setBusy(true);
    try {
      await changePassword(oldPassword, newPassword);
      const t = await login(usernameOrEmail, newPassword);
      saveSession({accessToken: t.accessToken, refreshToken: t.refreshToken, user: t.user, orgs: t.orgs});
      onDone(await fetchMe());
    } catch (err) {
      setError(err instanceof Error ? err.message : '修改失败，请重试');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-brand">
          <span className="login-brand-icon">🐾</span>
          <h1>首次登录须修改密码</h1>
          <p>账号由管理员开通，为保障安全请先设置新密码</p>
        </div>
        <form onSubmit={(e) => void submit(e)} className="login-form">
          <label>
            原密码（临时密码）
            <input
              type="password"
              value={oldPassword}
              onChange={(e) => setOldPassword(e.target.value)}
              autoFocus
              required
            />
          </label>
          <label>
            新密码<span className="field-hint">至少 8 位</span>
            <input
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              minLength={8}
              maxLength={100}
              required
            />
          </label>
          <label>
            确认新密码
            <input
              type="password"
              value={newPassword2}
              onChange={(e) => setNewPassword2(e.target.value)}
              required
            />
          </label>
          {error && <div className="form-error">{error}</div>}
          <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
            {busy ? '请稍候…' : '修改密码并进入'}
          </button>
        </form>
      </div>
    </div>
  );
}
