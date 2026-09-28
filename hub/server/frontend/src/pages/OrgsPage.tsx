import {useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {createOrg} from '../api';
import Modal from '../components/Modal';
import type {MeResponse} from '../types';

interface Props {
  me: MeResponse;
  currentOrgId: number | null;
  onSwitchOrg: (id: number) => void;
  onChanged: () => void;
}

const ROLE_LABELS: Record<string, string> = {
  owner: '所有者',
  admin: '管理员',
  member: '成员',
  guest: '访客',
};

/** 组织卡片列表 + 弹窗创建（slug 服务端派生）；卡片点击进入成员详情。 */
export default function OrgsPage({me, currentOrgId, onSwitchOrg, onChanged}: Props) {
  const navigate = useNavigate();
  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState('');
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  const submitCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    setBusy(true);
    try {
      const org = await createOrg(name.trim());
      setCreateOpen(false);
      setName('');
      onChanged();
      onSwitchOrg(org.id);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '创建失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>组织</h2>
          <p className="page-desc">组织是成员与资源的边界；点击卡片进入成员管理。</p>
        </div>
        <div className="page-head-right">
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => {
              setFormError('');
              setCreateOpen(true);
            }}
          >
            ＋ 创建组织
          </button>
        </div>
      </div>

      {me.orgs.length === 0 ? (
        <div className="empty-state">
          <h3>还没有组织</h3>
          <p>创建第一个组织，开始管理项目与成员。</p>
        </div>
      ) : (
        <div className="org-grid">
          {me.orgs.map((o) => (
            <div
              key={o.id}
              className={o.id === currentOrgId ? 'org-card current' : 'org-card'}
              onClick={() => navigate(`/orgs/${o.id}`)}
            >
              <div className="org-card-head">
                <span className="org-name">{o.name}</span>
                <span className={`badge role-${o.role}`}>{ROLE_LABELS[o.role] ?? o.role}</span>
                {o.id === currentOrgId && <span className="badge badge-current">当前</span>}
              </div>
              <div className="org-slug">@{o.slug}</div>
              <div className="org-card-actions" onClick={(e) => e.stopPropagation()}>
                {o.id !== currentOrgId && (
                  <button type="button" className="btn btn-sm" onClick={() => onSwitchOrg(o.id)}>
                    设为当前
                  </button>
                )}
                <button type="button" className="btn btn-sm" onClick={() => navigate(`/orgs/${o.id}`)}>
                  成员管理 →
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {createOpen && (
        <Modal title="创建组织" subtitle="标识（slug）由系统根据名称自动生成" onClose={() => setCreateOpen(false)}>
          <form className="modal-form" onSubmit={(e) => void submitCreate(e)}>
            <label>
              组织名称
              <input value={name} onChange={(e) => setName(e.target.value)} maxLength={128} autoFocus required />
            </label>
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
