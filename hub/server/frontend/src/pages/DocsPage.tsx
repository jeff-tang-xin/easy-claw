import {useCallback, useEffect, useMemo, useState} from 'react';
import {Route, Routes, useNavigate, useParams} from 'react-router-dom';
import {
  ApiRequestError,
  assignDoc,
  createDoc,
  deleteDoc,
  getDoc,
  getDocHistory,
  listDocs,
  listMembers,
  updateDoc,
} from '../api';
import Modal from '../components/Modal';
import type {DocDto, DocEventDto, DocListItemDto, MemberDto, ProjectDto} from '../types';

interface Props {
  project: ProjectDto;
  role: string | null;
  meUserId: number;
}

const TYPE_LABELS: Record<string, string> = {requirement: '需求', task: '任务'};
const ROLE_LABELS: Record<string, string> = {
  owner: '所有者',
  admin: '管理员',
  member: '成员',
  guest: '访客',
};
const TYPE_FILTERS = [
  {value: '', label: '全部'},
  {value: 'requirement', label: '需求'},
  {value: 'task', label: '任务'},
];

/** 项目空间内的文档模块：列表 + 编辑器（乐观锁）+ 版本历史，409 时并排对比手动合并。 */
export default function DocsPage({project, role, meUserId}: Props) {
  return (
    <Routes>
      <Route index element={<DocList project={project} role={role} meUserId={meUserId} />} />
      <Route path=":docId" element={<DocEditor project={project} role={role} meUserId={meUserId} />} />
    </Routes>
  );
}

// ============ 文档列表 ============
function DocList({project, role, meUserId}: Props) {
  const navigate = useNavigate();
  const readonly = role === 'guest';
  const [docs, setDocs] = useState<DocListItemDto[] | null>(null);
  const [error, setError] = useState('');
  const [filter, setFilter] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newType, setNewType] = useState('requirement');
  const [newParentId, setNewParentId] = useState<number | null>(null);
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);
  const [assignTarget, setAssignTarget] = useState<DocListItemDto | null>(null);

  const reload = useCallback(async () => {
    try {
      setDocs(await listDocs(project.id, filter || undefined));
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载文档失败');
    }
  }, [project.id, filter]);

  useEffect(() => {
    setDocs(null);
    void reload();
  }, [reload]);

  // task 父文档候选：同项目的需求（全部类型里挑 requirement）
  const requirements = useMemo(() => (docs ?? []).filter((d) => d.docType === 'requirement'), [docs]);

  const submitCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    setBusy(true);
    try {
      const created = await createDoc({
        projectId: project.id,
        title: newTitle.trim(),
        docType: newType,
        parentDocId: newType === 'task' ? newParentId : null,
      });
      setCreateOpen(false);
      navigate(`/projects/${project.id}/docs/${created.id}`);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '创建失败');
    } finally {
      setBusy(false);
    }
  };

  const remove = async (d: DocListItemDto) => {
    if (!window.confirm(`确定删除文档「${d.title}」？文档本身会被硬删，但历史版本仍保留可审计。`)) return;
    try {
      await deleteDoc(d.id);
      await reload();
    } catch (err) {
      alert(err instanceof Error ? err.message : '删除失败');
    }
  };

  const canManage = (d: DocListItemDto) => role === 'owner' || role === 'admin' || d.ownerUserId === meUserId;

  return (
    <div>
      <div className="filter-bar">
        <div className="doc-type-tabs">
          {TYPE_FILTERS.map((t) => (
            <button
              key={t.value}
              type="button"
              className={filter === t.value ? 'tab-btn active' : 'tab-btn'}
              onClick={() => setFilter(t.value)}
            >
              {t.label}
            </button>
          ))}
        </div>
        <div style={{flex: 1}} />
        {!readonly && (
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => {
              setNewTitle('');
              setNewType('requirement');
              setNewParentId(null);
              setFormError('');
              setCreateOpen(true);
            }}
          >
            ＋ 新建文档
          </button>
        )}
      </div>

      {error && <div className="form-error">{error}</div>}
      {docs == null && !error && <div className="empty-hint">加载中…</div>}
      {docs != null && docs.length === 0 && (
        <div className="empty-state">
          <h3>暂无文档</h3>
          <p>{readonly ? '该项目还没有可展示的文档。' : '点击右上角「新建文档」，先写一条需求或任务。'}</p>
        </div>
      )}

      {docs != null && docs.length > 0 && (
        <div className="card" style={{padding: 0}}>
          <table className="data-table doc-table">
            <thead>
              <tr>
                <th style={{width: 90}}>类型</th>
                <th>标题</th>
                <th style={{width: 110}}>负责人</th>
                <th style={{width: 80}}>版本</th>
                <th style={{width: 150}}>更新时间</th>
                <th className="col-actions">操作</th>
              </tr>
            </thead>
            <tbody>
              {docs.map((d) => (
                <tr key={d.id} className="row-clickable" onClick={() => navigate(`/projects/${project.id}/docs/${d.id}`)}>
                  <td>
                    <span className={`badge doc-type-${d.docType}`}>{TYPE_LABELS[d.docType] ?? d.docType}</span>
                  </td>
                  <td className="doc-title-cell">
                    {d.title}
                    {d.parentDocId != null && <span className="doc-task-mark"> ↳ 子任务</span>}
                  </td>
                  <td onClick={(e) => e.stopPropagation()}>{d.assigneeUsername || '—'}</td>
                  <td>v{d.version}</td>
                  <td>{d.updatedAt ? new Date(d.updatedAt).toLocaleString() : '—'}</td>
                  <td className="col-actions" onClick={(e) => e.stopPropagation()}>
                    {canManage(d) && !readonly && (
                      <>
                        <button type="button" className="btn btn-sm" onClick={() => setAssignTarget(d)}>
                          负责人
                        </button>
                        <button type="button" className="btn btn-sm btn-danger" onClick={() => void remove(d)}>
                          删除
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {createOpen && (
        <Modal title="新建文档" subtitle={`项目：${project.name}`} onClose={() => setCreateOpen(false)}>
          <form className="modal-form" onSubmit={(e) => void submitCreate(e)}>
            <label>
              类型
              <select value={newType} onChange={(e) => setNewType(e.target.value)}>
                <option value="requirement">需求</option>
                <option value="task">任务</option>
              </select>
            </label>
            {newType === 'task' && (
              <label>
                所属需求
                <select
                  value={newParentId ?? ''}
                  onChange={(e) => setNewParentId(e.target.value ? Number(e.target.value) : null)}
                  required
                >
                  <option value="" disabled>
                    选择同项目需求
                  </option>
                  {requirements.map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.title}
                    </option>
                  ))}
                </select>
              </label>
            )}
            {newType === 'task' && requirements.length === 0 && (
              <div className="form-error">该项目还没有需求，请先创建一条需求，再在其下挂任务。</div>
            )}
            <label>
              标题
              <input value={newTitle} onChange={(e) => setNewTitle(e.target.value)} maxLength={200} autoFocus required />
            </label>
            {formError && <div className="form-error">{formError}</div>}
            <div className="modal-actions">
              <button type="button" className="btn btn-ghost" onClick={() => setCreateOpen(false)}>
                取消
              </button>
              <button
                type="submit"
                className="btn btn-primary"
                disabled={busy || (newType === 'task' && requirements.length === 0)}
              >
                {busy ? '创建中…' : '创建并编辑'}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {assignTarget && (
        <AssignModal
          project={project}
          doc={assignTarget}
          onClose={() => setAssignTarget(null)}
          onChanged={() => {
            setAssignTarget(null);
            void reload();
          }}
        />
      )}
    </div>
  );
}

// ============ 指派负责人弹窗 ============
function AssignModal({
  project,
  doc,
  onClose,
  onChanged,
}: {
  project: ProjectDto;
  doc: DocListItemDto;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [members, setMembers] = useState<MemberDto[] | null>(null);
  const [value, setValue] = useState<string>(doc.assigneeUserId != null ? String(doc.assigneeUserId) : '');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    listMembers(project.orgId)
      .then((ms) => setMembers(ms.filter((m) => m.role !== 'guest')))
      .catch((err) => setError(err instanceof Error ? err.message : '加载成员失败'));
  }, [project.orgId]);

  const submit = async () => {
    setError('');
    setBusy(true);
    try {
      await assignDoc(doc.id, value ? Number(value) : null);
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : '指派失败');
      setBusy(false);
    }
  };

  return (
    <Modal title="设置负责人" subtitle={doc.title} onClose={onClose}>
      <div className="modal-form">
        <label>
          负责人（guest 为只读，不能作为负责人）
          <select value={value} onChange={(e) => setValue(e.target.value)}>
            <option value="">— 不指派 —</option>
            {members?.map((m) => (
              <option key={m.userId} value={m.userId}>
                {m.displayName || m.username}（{ROLE_LABELS[m.role] ?? m.role}）
              </option>
            ))}
          </select>
        </label>
        {error && <div className="form-error">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            取消
          </button>
          <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void submit()}>
            {busy ? '保存中…' : '保存'}
          </button>
        </div>
      </div>
    </Modal>
  );
}

// ============ 文档编辑器 ============
function DocEditor({project, role, meUserId}: Props) {
  const {docId: docIdParam} = useParams();
  const docId = Number(docIdParam);
  const navigate = useNavigate();
  const readonly = role === 'guest';
  const [doc, setDoc] = useState<DocDto | null>(null);
  const [error, setError] = useState('');
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [baseVersion, setBaseVersion] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [conflict, setConflict] = useState<DocDto | null>(null);

  const load = useCallback(async () => {
    try {
      const d = await getDoc(docId);
      setDoc(d);
      setTitle(d.title);
      setContent(d.content);
      setBaseVersion(d.version);
      setDirty(false);
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载文档失败');
    }
  }, [docId]);

  useEffect(() => {
    void load();
  }, [load]);

  const save = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const saved = await updateDoc(docId, {
        title: title.trim(),
        content,
        expectedVersion: baseVersion,
      });
      setDoc(saved);
      setBaseVersion(saved.version);
      setDirty(false);
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 409) {
        // 409：details 是后端返回的最新文档快照，弹并排对比让用户手动合并
        setConflict((err.details as DocDto) ?? null);
      } else {
        setError(err instanceof Error ? err.message : '保存失败');
      }
    } finally {
      setBusy(false);
    }
  }, [docId, title, content, baseVersion]);

  /** 冲突合并后的再保存：以服务端最新版本号为基线，提交用户合并后的标题/正文。 */
  const saveMerged = useCallback(
    async (mergedTitle: string, mergedContent: string, expectedVersion: number) => {
      setBusy(true);
      setError('');
      try {
        const saved = await updateDoc(docId, {
          title: mergedTitle.trim(),
          content: mergedContent,
          expectedVersion,
        });
        setDoc(saved);
        setTitle(saved.title);
        setContent(saved.content);
        setBaseVersion(saved.version);
        setDirty(false);
      } catch (err) {
        setError(err instanceof Error ? err.message : '合并后保存失败');
      } finally {
        setBusy(false);
      }
    },
    [docId],
  );

  if (error && !doc) {
    return (
      <div>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => navigate(`/projects/${project.id}/docs`)}>
          ← 返回列表
        </button>
        <div className="form-error" style={{marginTop: 12}}>
          {error}
        </div>
      </div>
    );
  }
  if (!doc) {
    return <div className="empty-hint">加载中…</div>;
  }

  return (
    <div className="doc-editor">
      <div className="doc-editor-head">
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => navigate(`/projects/${project.id}/docs`)}>
          ← 返回列表
        </button>
        <div style={{flex: 1}} />
        <span className={`badge doc-type-${doc.docType}`}>{TYPE_LABELS[doc.docType] ?? doc.docType}</span>
        <span className="doc-version-tag">v{doc.version}</span>
        <button type="button" className="btn btn-sm" onClick={() => setShowHistory(true)}>
          历史版本
        </button>
        {!readonly && (
          <button type="button" className="btn btn-primary btn-sm" disabled={busy || !dirty} onClick={() => void save()}>
            {busy ? '保存中…' : dirty ? '保存' : '已保存'}
          </button>
        )}
      </div>

      <div className="doc-meta-row">
        <span>负责人：{doc.assigneeUsername || '未指派'}</span>
        <span className="doc-meta-dot">·</span>
        <span>创建者：{doc.ownerUsername || `#${doc.ownerUserId}`}</span>
        <span className="doc-meta-dot">·</span>
        <span>更新于 {doc.updatedAt ? new Date(doc.updatedAt).toLocaleString() : '—'}</span>
      </div>

      {readonly && <div className="form-error" style={{marginBottom: 12}}>访客只读，不能编辑本文档。</div>}
      {error && <div className="form-error" style={{marginBottom: 12}}>{error}</div>}

      <input
        className="doc-title-input"
        value={title}
        disabled={readonly}
        onChange={(e) => {
          setTitle(e.target.value);
          setDirty(true);
        }}
        maxLength={200}
        placeholder="文档标题"
      />
      <textarea
        className="doc-content-input"
        value={content}
        disabled={readonly}
        onChange={(e) => {
          setContent(e.target.value);
          setDirty(true);
        }}
        placeholder="在此输入正文…（纯文本，支持换行）"
      />

      {showHistory && <HistoryModal docId={doc.id} onClose={() => setShowHistory(false)} />}
      {conflict && (
        <ConflictModal
          latest={conflict}
          myTitle={title}
          myContent={content}
          onClose={() => setConflict(null)}
          onAdoptServer={(latest) => {
            // 放弃本地、采用服务端最新版作为编辑基线
            setTitle(latest.title);
            setContent(latest.content);
            setBaseVersion(latest.version);
            setDoc(latest);
            setDirty(false);
            setConflict(null);
          }}
          onMerged={(mergedTitle, mergedContent, latest) => {
            // 以合并后的内容 + 服务端最新版本号为基线，立即再保存一次
            setConflict(null);
            void saveMerged(mergedTitle, mergedContent, latest.version);
          }}
        />
      )}
    </div>
  );
}

// ============ 409 冲突：并排对比 + 手动合并 ============
function ConflictModal({
  latest,
  myTitle,
  myContent,
  onClose,
  onAdoptServer,
  onMerged,
}: {
  latest: DocDto;
  myTitle: string;
  myContent: string;
  onClose: () => void;
  onAdoptServer: (latest: DocDto) => void;
  onMerged: (mergedTitle: string, mergedContent: string, latest: DocDto) => void;
}) {
  // 合并工作区：预填本地内容，用户可对照左侧服务端最新版手动改
  const [mergedTitle, setMergedTitle] = useState(myTitle);
  const [mergedContent, setMergedContent] = useState(myContent);

  return (
    <Modal
      title="保存冲突（版本已被他人推进）"
      subtitle={`服务端已是 v${latest.version}，你的编辑基于更早版本。请对照后手动合并。`}
      onClose={onClose}
      width={920}
    >
      <div className="conflict-grid">
        <div className="conflict-col">
          <div className="conflict-col-head">服务端最新版（只读）</div>
          <input className="doc-title-input" value={latest.title} readOnly />
          <textarea className="doc-content-input conflict-readonly" value={latest.content} readOnly />
        </div>
        <div className="conflict-col">
          <div className="conflict-col-head">我的合并结果（可编辑）</div>
          <input
            className="doc-title-input"
            value={mergedTitle}
            onChange={(e) => setMergedTitle(e.target.value)}
          />
          <textarea
            className="doc-content-input"
            value={mergedContent}
            onChange={(e) => setMergedContent(e.target.value)}
          />
        </div>
      </div>
      <div className="modal-actions conflict-actions">
        <button type="button" className="btn btn-ghost" onClick={onClose}>
          取消（保留我的编辑区）
        </button>
        <button type="button" className="btn" onClick={() => onAdoptServer(latest)}>
          放弃本地，采用服务端版
        </button>
        <button
          type="button"
          className="btn btn-primary"
          onClick={() => onMerged(mergedTitle, mergedContent, latest)}
        >
          保存合并结果
        </button>
      </div>
    </Modal>
  );
}

// ============ 版本历史 ============
function HistoryModal({docId, onClose}: {docId: number; onClose: () => void}) {
  const [events, setEvents] = useState<DocEventDto[] | null>(null);
  const [error, setError] = useState('');
  const [active, setActive] = useState<DocEventDto | null>(null);

  useEffect(() => {
    getDocHistory(docId)
      .then((evs) => {
        setEvents(evs);
        setActive(evs[0] ?? null);
      })
      .catch((err) => setError(err instanceof Error ? err.message : '加载历史失败'));
  }, [docId]);

  return (
    <Modal title="版本历史" subtitle="每次保存生成一个不可变快照；文档删除后历史仍保留。" onClose={onClose} width={920}>
      {error && <div className="form-error">{error}</div>}
      {events == null && !error && <div className="empty-hint">加载中…</div>}
      {events != null && (
        <div className="history-grid">
          <div className="history-list">
            {events.map((e) => (
              <button
                key={e.id}
                type="button"
                className={active?.id === e.id ? 'history-item active' : 'history-item'}
                onClick={() => setActive(e)}
              >
                <span className="history-version">v{e.version}</span>
                <span className="history-actor">{e.actorUsername || `#${e.actorUserId}`}</span>
                <span className="history-time">{e.createdAt ? new Date(e.createdAt).toLocaleString() : '—'}</span>
              </button>
            ))}
          </div>
          <div className="history-detail">
            {active && (
              <>
                <div className="history-detail-title">{active.title}</div>
                <pre className="history-detail-content">{active.content}</pre>
              </>
            )}
          </div>
        </div>
      )}
    </Modal>
  );
}