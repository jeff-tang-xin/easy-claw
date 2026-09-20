import {useState} from 'react';
import {ApiRequestError, fetchMe, login} from '../api';
import {saveSession} from '../auth';
import ChangePasswordCard from '../components/ChangePasswordCard';
import type {MeResponse} from '../types';

interface Props {
  onLoggedIn: (me: MeResponse) => void;
}

/**
 * 登录。公开注册已取消：初始 admin 由启动引导创建，后续用户由平台管理员开通。
 * 首登 mustChangePassword=true 时先落会话（改密要靠 access token），切到强制改密卡片。
 */
export default function LoginPage({onLoggedIn}: Props) {
  const [usernameOrEmail, setUsernameOrEmail] = useState('');
  const [password, setPassword] = useState('');
  const [forceChange, setForceChange] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const t = await login(usernameOrEmail.trim(), password);
      saveSession({accessToken: t.accessToken, refreshToken: t.refreshToken, user: t.user, orgs: t.orgs});
      if (t.user.mustChangePassword) {
        setForceChange(true);
        return;
      }
      onLoggedIn(await fetchMe());
    } catch (err) {
      // 密码已过期（超过 60 天有效期）：禁止登录，提示联系平台管理员重置
      if (err instanceof ApiRequestError && err.code === 'PASSWORD_EXPIRED') {
        setError(err.message || '密码已过期，请联系平台管理员重置密码后再登录。');
      } else {
        setError(err instanceof Error ? err.message : '登录失败，请重试');
      }
    } finally {
      setBusy(false);
    }
  };

  if (forceChange) {
    return (
      <ChangePasswordCard
        usernameOrEmail={usernameOrEmail.trim()}
        defaultOldPassword={password}
        onDone={onLoggedIn}
      />
    );
  }

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-brand">
          <span className="login-brand-icon">🐾</span>
          <h1>Easy-Claw Hub</h1>
          <p>主子架构云端控制台</p>
        </div>
        <form onSubmit={(e) => void submit(e)} className="login-form">
          <label>
            用户名或邮箱
            <input
              value={usernameOrEmail}
              onChange={(e) => setUsernameOrEmail(e.target.value)}
              autoFocus
              required
            />
          </label>
          <label>
            密码
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </label>
          {error && <div className="form-error">{error}</div>}
          <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
            {busy ? '请稍候…' : '登录'}
          </button>
        </form>
      </div>
    </div>
  );
}
