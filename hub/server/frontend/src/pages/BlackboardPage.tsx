import {useCallback, useEffect, useState} from 'react';
import {
  appendBlackboardEntry,
  archiveBlackboardEntry,
  listBlackboardActive,
  listBlackboardArchives,
  unarchiveBlackboardEntry,
} from '../api';
import type {BlackboardEntryDto, ProjectDto} from '../types';

interface Props {
  project: ProjectDto;
  role: string | null;
  meUserId: number;
}

/**
 * 项目共享黑板（A4）：追加型记录本，团队共享，按项目归类。
 * 活跃条目 + 追加 + 归档（状态标签，幂等）；归档列表单独查看，可取消归档。
 * 归档 / 取消归档权限：作者本人或组织 owner/admin（服务端强制校验，按钮按同口径显隐）。
 */
export default function BlackboardPage({project, role, meUserId}: Props) {
  const [active, setActive] = useState<BlackboardEntryDto[] | null>(null);
  const [archives, setArchives] = useState<BlackboardEntryDto[] | null>(null);
  const [showArchives, setShowArchives] = useState(false);
  const [newContent, setNewContent] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const canWrite = role !== 'guest' && role !== null;

  const load = useCallback(async () => {
    try {
      const [a, ar] = await Promise.all([
        listBlackboardActive(project.id),
        listBlackboardArchives(project.id),
      ]);
      setActive(a);
      setArchives(ar);
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载黑板失败');
    }
  }, [project.id]);

  useEffect(() => {
    void load();
  }, [load]);

  const append = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newContent.trim()) return;
    setBusy(true);
    setError('');
    try {
      await appendBlackboardEntry(project.id, newContent.trim());
      setNewContent('');
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : '追加失败');
    } finally {
      setBusy(false);
    }
  };

  const archive = async (entry: BlackboardEntryDto) => {
    if (!window.confirm('确定归档这条记录？归档后移入归档列表，可随时查看。')) return;
    setError('');
    try {
      await archiveBlackboardEntry(entry.id);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : '归档失败');
    }
  };

  const unarchive = async (entry: BlackboardEntryDto) => {
    setError('');
    try {
      await unarchiveBlackboardEntry(entry.id);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : '取消归档失败');
    }
  };

  /** 归档 / 取消归档权限：作者本人或组织 owner/admin（与服务端口径一致）。 */
  const canArchive = (entry: BlackboardEntryDto) =>
    role === 'owner' || role === 'admin' || entry.authorUserId === meUserId;

  const list = showArchives ? archives : active;

  return (
    <div className="ps-blackboard">
      {canWrite && (
        <form className="card" onSubmit={append}>
          <h3 className="ps-card-title">追加记录</h3>
          <textarea
            rows={3}
            value={newContent}
            onChange={(e) => setNewContent(e.target.value)}
            maxLength={5000}
            placeholder="写一条共享记录，团队成员可见…"
          />
          {error && <div className="form-error">{error}</div>}
          <div className="modal-actions">
            <button type="submit" className="btn btn-primary" disabled={busy || !newContent.trim()}>
              {busy ? '追加中…' : '追加'}
            </button>
          </div>
        </form>
      )}

      <div className="card">
        <div className="doc-editor-head">
          <h3 className="ps-card-title">
            {showArchives ? `归档记录（${archives?.length ?? '…'}）` : `黑板（${active?.length ?? '…'}）`}
          </h3>
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={() => setShowArchives((v) => !v)}
          >
            {showArchives ? '查看活跃记录' : '查看归档'}
          </button>
        </div>

        {list === null ? (
          <div className="empty-hint">加载中…</div>
        ) : list.length === 0 ? (
          <div className="empty-hint">{showArchives ? '暂无归档记录。' : '黑板是空的，追加第一条记录吧。'}</div>
        ) : (
          <ul className="bb-list">
            {list.map((entry) => (
              <li key={entry.id} className="bb-item">
                <div className="bb-content">{entry.content}</div>
                <div className="bb-meta">
                  <span>{entry.authorUsername ?? `#${entry.authorUserId}`}</span>
                  <span className="doc-meta-dot">·</span>
                  <span>{entry.createdAt ? new Date(entry.createdAt).toLocaleString() : '—'}</span>
                  {!showArchives && canArchive(entry) && (
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      onClick={() => void archive(entry)}
                    >
                      归档
                    </button>
                  )}
                  {showArchives && canArchive(entry) && (
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      onClick={() => void unarchive(entry)}
                    >
                      取消归档
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}