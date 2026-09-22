import {useCallback, useEffect, useState} from 'react';
import {Route, Routes, useNavigate, useParams} from 'react-router-dom';
import {
  ApiRequestError,
  createKnowledgeItem,
  deleteKnowledgeItem,
  getKnowledgeHistory,
  getKnowledgeHistoryVersion,
  getKnowledgeItem,
  listKnowledgeItems,
  updateKnowledgeItem,
} from '../api';
import type {
  KnowledgeHistoryItemDto,
  KnowledgeHistoryVersionDto,
  KnowledgeItemDto,
  KnowledgeItemListItemDto,
  ProjectDto,
} from '../types';

interface Props {
  project: ProjectDto;
  role: string | null;
  meUserId: number;
}

/**
 * 项目知识库（A3-S1）：条目 CRUD + 乐观锁并发（409 冲突合并）+ 软删 + 版本历史。
 * 路由：/projects/:pid/knowledge（列表）与 /projects/:pid/knowledge/:itemId（编辑器）。
 */
export default function KnowledgePage({project, role, meUserId}: Props) {
  return (
    <Routes>
      <Route index element={<KnowledgeList project={project} role={role} meUserId={meUserId} />} />
      <Route path=":itemId" element={<KnowledgeEditor project={project} role={role} meUserId={meUserId} />} />
    </Routes>
  );
}

function KnowledgeList({project, role, meUserId}: Props) {
  const navigate = useNavigate();
  const [items, setItems] = useState<KnowledgeItemListItemDto[] | null>(null);
  const [error, setError] = useState('');
  const [newTopic, setNewTopic] = useState('');
  const [newSummary, setNewSummary] = useState('');
  const [newContent, setNewContent] = useState('');
  const [creating, setCreating] = useState(false);
  // 检索：queryInput 为输入框实时值，query 为已生效的检索词（回车/按钮提交后同步）
  const [queryInput, setQueryInput] = useState('');
  const [query, setQuery] = useState('');

  const canWrite = role !== 'guest' && role !== null;

  const load = useCallback(async () => {
    try {
      setItems(await listKnowledgeItems(project.id, query));
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载知识库失败');
    }
  }, [project.id, query]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 提交检索：把输入框值生效（load 由 query 变化驱动）；空白等同清除检索。 */
  const submitSearch = () => {
    setQuery(queryInput.trim());
  };

  const clearSearch = () => {
    setQueryInput('');
    setQuery('');
  };

  const create = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTopic.trim()) return;
    setCreating(true);
    try {
      const created = await createKnowledgeItem({
        projectId: project.id,
        topic: newTopic.trim(),
        summary: newSummary.trim() || null,
        content: newContent.trim() || null,
      });
      setNewTopic('');
      setNewSummary('');
      setNewContent('');
      navigate(`${created.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : '创建失败');
    } finally {
      setCreating(false);
    }
  };

  const remove = async (item: KnowledgeItemListItemDto) => {
    if (!window.confirm(`确定删除知识条目「${item.topic}」？历史版本保留留痕。`)) return;
    try {
      await deleteKnowledgeItem(item.id);
      setItems((prev) => (prev ? prev.filter((i) => i.id !== item.id) : prev));
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败');
    }
  };

  const canManage = (item: KnowledgeItemListItemDto) =>
    role === 'owner' || role === 'admin' || item.ownerUserId === meUserId;

  return (
    <div className="ps-knowledge">
      {canWrite && (
        <form className="card" onSubmit={create}>
          <h3 className="ps-card-title">新增知识条目</h3>
          <label>
            主题（topic）
            <input
              value={newTopic}
              onChange={(e) => setNewTopic(e.target.value)}
              maxLength={200}
              placeholder="一句话主题，如「网关鉴权流程」"
              required
            />
          </label>
          <label>
            摘要（可选）
            <input
              value={newSummary}
              onChange={(e) => setNewSummary(e.target.value)}
              maxLength={500}
              placeholder="列表页展示的简短摘要"
            />
          </label>
          <label>
            正文（可选）
            <textarea
              rows={4}
              value={newContent}
              onChange={(e) => setNewContent(e.target.value)}
              maxLength={50000}
              placeholder="详细内容，Markdown 或纯文本"
            />
          </label>
          {error && <div className="form-error">{error}</div>}
          <div className="modal-actions">
            <button type="submit" className="btn btn-primary" disabled={creating || !newTopic.trim()}>
              {creating ? '创建中…' : '创建'}
            </button>
          </div>
        </form>
      )}

      <div className="card">
        <div className="doc-editor-head">
          <h3 className="ps-card-title">
            知识条目（{items?.length ?? '…'}{query ? `，检索「${query}」` : ''}）
          </h3>
        </div>
        <form
          className="filter-bar"
          onSubmit={(e) => {
            e.preventDefault();
            submitSearch();
          }}
        >
          <input
            value={queryInput}
            onChange={(e) => setQueryInput(e.target.value)}
            maxLength={200}
            placeholder="搜索主题 / 摘要 / 正文，多词空格分隔（AND）"
          />
          <button type="submit" className="btn btn-primary btn-sm">
            搜索
          </button>
          {query && (
            <button type="button" className="btn btn-ghost btn-sm" onClick={clearSearch}>
              清除
            </button>
          )}
        </form>
        {items === null ? (
          <div className="empty-hint">加载中…</div>
        ) : items.length === 0 ? (
          <div className="empty-hint">
            {query ? '没有匹配的知识条目，换个关键词或清除检索。' : '还没有知识条目，点击上方创建。'}
          </div>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>主题</th>
                <th>摘要</th>
                <th>版本</th>
                <th>维护者</th>
                <th>更新于</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id}>
                  <td className="doc-title-cell">
                    <button type="button" className="link-btn" onClick={() => navigate(`${item.id}`)}>
                      {item.topic}
                    </button>
                  </td>
                  <td className="text-muted">{item.summary || '—'}</td>
                  <td>
                    <span className="doc-version-tag">v{item.version}</span>
                  </td>
                  <td>{item.updatedByUsername ?? item.ownerUsername ?? `#${item.ownerUserId}`}</td>
                  <td className="text-muted">
                    {item.updatedAt ? new Date(item.updatedAt).toLocaleString() : '—'}
                  </td>
                  <td className="row-actions">
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => navigate(`${item.id}`)}>
                      查看
                    </button>
                    {canManage(item) && (
                      <button
                        type="button"
                        className="btn btn-ghost btn-sm btn-danger-text"
                        onClick={() => void remove(item)}
                      >
                        删除
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

function KnowledgeEditor({project, role, meUserId}: Props) {
  const {itemId} = useParams();
  const navigate = useNavigate();
  const id = Number(itemId);
  const [item, setItem] = useState<KnowledgeItemDto | null>(null);
  const [topic, setTopic] = useState('');
  const [summary, setSummary] = useState('');
  const [content, setContent] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [conflict, setConflict] = useState<KnowledgeItemDto | null>(null);
  const [history, setHistory] = useState<KnowledgeHistoryItemDto[] | null>(null);
  const [activeVersion, setActiveVersion] = useState<KnowledgeHistoryVersionDto | null>(null);

  const canWrite = role !== 'guest' && role !== null;
  const canManage = item && (role === 'owner' || role === 'admin' || item.ownerUserId === meUserId);

  const load = useCallback(async () => {
    try {
      const d = await getKnowledgeItem(id);
      setItem(d);
      setTopic(d.topic);
      setSummary(d.summary ?? '');
      setContent(d.content ?? '');
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载知识条目失败');
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!item) return;
    setBusy(true);
    setError('');
    setConflict(null);
    try {
      const saved = await updateKnowledgeItem(id, {
        topic: topic.trim(),
        summary: summary.trim() || null,
        content: content.trim() || null,
        expectedVersion: item.version,
      });
      setItem(saved);
      setTopic(saved.topic);
      setSummary(saved.summary ?? '');
      setContent(saved.content ?? '');
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 409) {
        setConflict((err.details as KnowledgeItemDto) ?? null);
      } else {
        setError(err instanceof Error ? err.message : '保存失败');
      }
    } finally {
      setBusy(false);
    }
  };

  const openHistory = async () => {
    try {
      setHistory(await getKnowledgeHistory(id));
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载历史失败');
    }
  };

  const openVersion = async (version: number) => {
    try {
      setActiveVersion(await getKnowledgeHistoryVersion(id, version));
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载历史版本失败');
    }
  };

  if (!item) {
    return (
      <div className="ps-knowledge">
        <div className="card">
          <h3 className="ps-card-title">知识条目</h3>
          {error ? <div className="form-error">{error}</div> : <div className="empty-hint">加载中…</div>}
          <div className="modal-actions">
            <button type="button" className="btn btn-ghost" onClick={() => navigate('..')}>
              ← 返回列表
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="ps-knowledge">
      <div className="card">
        <div className="doc-editor-head">
          <h3 className="ps-card-title">编辑知识条目</h3>
          <span className="doc-version-tag">v{item.version}</span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => void openHistory()}>
            历史版本
          </button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => navigate('..')}>
            ← 返回列表
          </button>
        </div>
        <div className="doc-meta-row">
          <span>维护者：{item.updatedByUsername ?? item.ownerUsername ?? `#${item.ownerUserId}`}</span>
          <span className="doc-meta-dot">·</span>
          <span>更新于：{item.updatedAt ? new Date(item.updatedAt).toLocaleString() : '—'}</span>
        </div>

        {conflict && (
          <ConflictBanner
            latest={conflict}
            onAdoptServer={(latest) => {
              setItem(latest);
              setTopic(latest.topic);
              setSummary(latest.summary ?? '');
              setContent(latest.content ?? '');
              setConflict(null);
            }}
            onMerged={async (mergedTopic, mergedSummary, mergedContent, latest) => {
              try {
                const saved = await updateKnowledgeItem(id, {
                  topic: mergedTopic,
                  summary: mergedSummary,
                  content: mergedContent,
                  expectedVersion: latest.version,
                });
                setItem(saved);
                setTopic(saved.topic);
                setSummary(saved.summary ?? '');
                setContent(saved.content ?? '');
                setConflict(null);
              } catch (err) {
                setError(err instanceof Error ? err.message : '合并保存失败');
              }
            }}
          />
        )}

        <form onSubmit={save}>
          <label>
            主题（topic）
            <input
              className="doc-title-input"
              value={topic}
              onChange={(e) => setTopic(e.target.value)}
              maxLength={200}
              disabled={!canWrite}
              required
            />
          </label>
          <label>
            摘要
            <input
              value={summary}
              onChange={(e) => setSummary(e.target.value)}
              maxLength={500}
              disabled={!canWrite}
            />
          </label>
          <label>
            正文
            <textarea
              className="doc-content-input"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              maxLength={50000}
              disabled={!canWrite}
            />
          </label>
          {error && <div className="form-error">{error}</div>}
          {canWrite && (
            <div className="modal-actions">
              <button type="submit" className="btn btn-primary" disabled={busy || !topic.trim()}>
                {busy ? '保存中…' : '保存'}
              </button>
            </div>
          )}
          {!canWrite && <div className="form-error" style={{marginTop: 10}}>访客只读，不能编辑知识条目。</div>}
        </form>
      </div>

      {history && (
        <HistoryPanel
          history={history}
          activeVersion={activeVersion}
          onOpenVersion={(v) => void openVersion(v)}
          onClose={() => {
            setHistory(null);
            setActiveVersion(null);
          }}
        />
      )}
    </div>
  );
}

function ConflictBanner({
  latest,
  onAdoptServer,
  onMerged,
}: {
  latest: KnowledgeItemDto;
  onAdoptServer: (latest: KnowledgeItemDto) => void;
  onMerged: (topic: string, summary: string, content: string, latest: KnowledgeItemDto) => void;
}) {
  const [mergedTopic, setMergedTopic] = useState(latest.topic);
  const [mergedSummary, setMergedSummary] = useState(latest.summary ?? '');
  const [mergedContent, setMergedContent] = useState(latest.content ?? '');

  return (
    <div className="conflict-banner">
      <div className="conflict-title">⚠️ 版本冲突：他人已更新此条目（最新 v{latest.version}）。</div>
      <p className="page-desc">你的编辑基于旧版本。可「采用服务器版本」丢弃你的改动，或手动合并后重新保存。</p>
      <label>
        主题
        <input value={mergedTopic} onChange={(e) => setMergedTopic(e.target.value)} maxLength={200} />
      </label>
      <label>
        摘要
        <input value={mergedSummary} onChange={(e) => setMergedSummary(e.target.value)} maxLength={500} />
      </label>
      <label>
        正文
        <textarea
          rows={6}
          value={mergedContent}
          onChange={(e) => setMergedContent(e.target.value)}
          maxLength={50000}
        />
      </label>
      <div className="modal-actions">
        <button
          type="button"
          className="btn btn-primary"
          onClick={() => onMerged(mergedTopic.trim(), mergedSummary.trim(), mergedContent.trim(), latest)}
        >
          合并保存
        </button>
        <button type="button" className="btn btn-ghost" onClick={() => onAdoptServer(latest)}>
          采用服务器版本
        </button>
      </div>
    </div>
  );
}

function HistoryPanel({
  history,
  activeVersion,
  onOpenVersion,
  onClose,
}: {
  history: KnowledgeHistoryItemDto[];
  activeVersion: KnowledgeHistoryVersionDto | null;
  onOpenVersion: (version: number) => void;
  onClose: () => void;
}) {
  return (
    <div className="card">
      <div className="doc-editor-head">
        <h3 className="ps-card-title">历史版本</h3>
        <button type="button" className="btn btn-ghost btn-sm" onClick={onClose}>
          关闭
        </button>
      </div>
      <table className="data-table">
        <thead>
          <tr>
            <th>版本</th>
            <th>主题</th>
            <th>操作人</th>
            <th>时间</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {history.map((h) => (
            <tr key={h.version}>
              <td>
                <span className="doc-version-tag">v{h.version}</span>
              </td>
              <td>{h.topic}</td>
              <td>{h.actorUsername ?? `#${h.actorUserId ?? '?'}`}</td>
              <td className="text-muted">{h.createdAt ? new Date(h.createdAt).toLocaleString() : '—'}</td>
              <td>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => onOpenVersion(h.version)}>
                  查看
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {activeVersion && (
        <div className="history-version">
          <div className="doc-meta-row">
            <span className="doc-version-tag">v{activeVersion.version}</span>
            <span>· {activeVersion.topic}</span>
            <span className="doc-meta-dot">·</span>
            <span>{activeVersion.actorUsername ?? `#${activeVersion.actorUserId ?? '?'}`}</span>
            <span className="doc-meta-dot">·</span>
            <span>{activeVersion.createdAt ? new Date(activeVersion.createdAt).toLocaleString() : '—'}</span>
          </div>
          <pre className="history-content">{activeVersion.content || '（空正文）'}</pre>
        </div>
      )}
    </div>
  );
}