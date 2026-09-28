import {useEffect, useState} from 'react';
import {getJson} from '../api';

interface WorkspaceSummary {
  workspaceId: string;
  name: string;
}

interface KnowledgeEntry {
  topic: string;
  summary: string;
  lastModified: number;
  fileSize: number;
}

function fmtTime(ms: number): string {
  if (!ms) return '-';
  return new Date(ms).toLocaleString('zh-CN', { hour12: false });
}

function fmtSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}

export default function KnowledgePage() {
  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([]);
  const [wsId, setWsId] = useState('');
  const [entries, setEntries] = useState<KnowledgeEntry[]>([]);
  const [activeTopic, setActiveTopic] = useState('');
  const [content, setContent] = useState('');
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  // 工作区列表：进入页面即加载，并默认选中第一个
  useEffect(() => {
    getJson<WorkspaceSummary[]>('/api/workspaces')
      .then((list) => {
        setWorkspaces(list);
        if (list.length > 0) setWsId((cur) => cur || list[0].workspaceId);
      })
      .catch((e) => setError(String(e)));
  }, []);

  // 切换工作区 → 重新拉条目清单，并清空右侧正文（避免展示上个工作区的残留内容）
  useEffect(() => {
    if (!wsId) return;
    setActiveTopic('');
    setContent('');
    setLoading(true);
    getJson<KnowledgeEntry[]>(`/api/knowledge/entries?workspaceId=${encodeURIComponent(wsId)}`)
      .then((list) => {
        setEntries(list);
        setError('');
        if (list.length > 0) setActiveTopic(list[0].topic);
      })
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false));
  }, [wsId]);

  // 选中条目 → 拉正文
  useEffect(() => {
    if (!wsId || !activeTopic) return;
    setLoading(true);
    const url = `/api/knowledge/entry?workspaceId=${encodeURIComponent(wsId)}`
      + `&topic=${encodeURIComponent(activeTopic)}`;
    getJson<{ topic: string; content: string }>(url)
      .then((res) => {
        setContent(res.content);
        setError('');
      })
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false));
  }, [wsId, activeTopic]);

  const refresh = () => {
    if (!wsId) return;
    setLoading(true);
    getJson<KnowledgeEntry[]>(`/api/knowledge/entries?workspaceId=${encodeURIComponent(wsId)}`)
      .then((list) => {
        setEntries(list);
        setError('');
      })
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false));
  };

  const kw = keyword.trim().toLowerCase();
  const shown = entries.filter((e) => {
    if (!kw) return true;
    return (e.topic || '').toLowerCase().includes(kw)
      || (e.summary || '').toLowerCase().includes(kw);
  });

  const totalSize = entries.reduce((sum, e) => sum + (e.fileSize || 0), 0);

  return (
    <div className="page">
      <h1 className="page-title">📚 知识库</h1>
      <p className="hint" style={{ marginTop: -8 }}>
        跨会话的长期知识沉淀，每次对话自动注入系统提示。由 AI 经 knowledge_write 工具从记录本「晋升」而来。
      </p>
      {error && <div className="error-box">{error}</div>}

      <div className="card" style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
          <label>
            工作区&nbsp;
            <select value={wsId} onChange={(e) => setWsId(e.target.value)}>
              {workspaces.length === 0 && <option value="">（无）</option>}
              {workspaces.map((w) => (
                <option key={w.workspaceId} value={w.workspaceId}>{w.name || w.workspaceId}</option>
              ))}
            </select>
          </label>
          <input
            placeholder="搜索条目名 / 摘要"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            style={{ flex: '1 1 200px', minWidth: 160 }}
          />
          <span className="hint">
            共 {entries.length} 条 · {fmtSize(totalSize)}
          </span>
          <button className="btn small" onClick={refresh} disabled={loading}>
            {loading ? '加载中…' : '刷新'}
          </button>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
        <div className="card" style={{ flex: '0 0 280px' }}>
          <h3 style={{ marginTop: 0 }}>条目（{shown.length}）</h3>
          {entries.length === 0 && !loading && (
            <div className="hint">
              该工作区还没有知识条目。AI 会在任务收尾时用 knowledge_write 把有长期价值的结论写入。
            </div>
          )}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {shown.map((e) => (
              <button
                key={e.topic}
                className={'btn small' + (e.topic === activeTopic ? ' primary' : '')}
                onClick={() => setActiveTopic(e.topic)}
                style={{ textAlign: 'left', flexDirection: 'column', alignItems: 'flex-start' }}
                title={e.summary}
              >
                <span style={{ fontWeight: 600, wordBreak: 'break-all' }}>{e.topic}</span>
                <span className="hint" style={{ fontSize: 11 }}>
                  {fmtSize(e.fileSize)} · {fmtTime(e.lastModified)}
                </span>
              </button>
            ))}
          </div>
        </div>

        <div className="card" style={{ flex: 1, minWidth: 0 }}>
          {!activeTopic && <div className="hint">请选择左侧的一个条目。</div>}
          {activeTopic && (
            <>
              <h3 style={{ marginTop: 0, wordBreak: 'break-all' }}>{activeTopic}</h3>
              <pre style={{
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
                margin: 0,
                fontFamily: 'inherit',
                lineHeight: 1.6,
              }}>{content}</pre>
            </>
          )}
        </div>
      </div>
    </div>
  );
}