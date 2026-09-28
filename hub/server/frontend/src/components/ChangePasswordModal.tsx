import {useState} from 'react';
import {changePassword, fetchMe} from '../api';
import Modal from './Modal';
import type {MeResponse} from '../types';

interface Props {
  onClose: () => void;
  /** 改密成功：用最新 me（密码到期时间已刷新）回灌父级 */
  onChanged: (me: MeResponse) => void;
}

/**
 * 日常修改密码弹窗（密码临期提醒 / 用户自助改密入口使用）。
 * 与首登强制改密 {@link ChangePasswordCard} 的区别：当前会话 token 不带 mcp 标记，
 * 改密成功后旧 token 仍有效，只需 fetchMe 刷新密码到期时间，<b>无需重新登录</b>。
 * 成功链路：change-password → fetchMe → onChanged。
 */
export default function ChangePasswordModal({onClose, onChanged}: Props) {
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [newPassword2, setNewPassword2] = useState('');
  const [error, setError] = useState('');
  const [done, setDone] = useState(false);
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
      onChanged(await fetchMe());
      setDone(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : '修改失败，请重试');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title="修改密码" subtitle="修改成功后密码有效期重新计算为 60 天" onClose={onClose}>
      {done ? (
        <div>
          <p>密码已修改成功，新的密码有效期为 60 天。</p>
          <div className="modal-actions">
            <button type="button" className="btn btn-primary" onClick={onClose}>
              完成
            </button>
          </div>
        </div>
      ) : (
        <form className="modal-form" onSubmit={(e) => void submit(e)}>
          <label>
            原密码
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
          <div className="modal-actions">
            <button type="button" className="btn btn-ghost" onClick={onClose}>
              取消
            </button>
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {busy ? '请稍候…' : '确认修改'}
            </button>
          </div>
        </form>
      )}
    </Modal>
  );
}
