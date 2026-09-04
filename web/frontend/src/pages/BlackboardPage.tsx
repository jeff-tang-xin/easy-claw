import {useEffect, useState} from 'react';
import {getJson} from '../api';

interface WorkspaceSummary {
  workspaceId: string;
  name: string;
}

interface BlackboardBook {
  key: string;
  entries: number;
  lastModified: number;
}

interface BlackboardEntry {
  seq: number;
  ts: string;
  author: string;
  type: string;
  content: string;
}

const TYPE_META: Record<string, { icon: string; label: string; cls: string }> = {
  finding: { icon: '🔍', label: '发现', cls: 'blue' },
  risk: { icon: '⚠️', label: '风险', cls: 'red' },
  conclusion: { icon: '✅', label: '结论', cls: 'green' },
  note: { icon: '📝', label: '记录', cls: 'gray' },
};

const TYPE_FILTERS = ['all', 'finding', 'risk', 'conclusion', 'note'];

function fmtTime(ms: number): string {
  if (!ms) return '-';
  return new Date(ms).toLocaleString('zh-CN', { hour12: false });
}

export default function BlackboardPage() {
  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([]);
  const [wsId, setWsId] = useState('');
  const [books, setBooks] = useState<BlackboardBook[]>([]);
  const [activeKey, setActiveKey] = useState('');
  const [entries, setEntries] = useState<BlackboardEntry[]>([]);
  const [typeFilter, setTypeFilter] = useState('all');
  const [keyword, setKeyword] = useState('');
  const [limit, setLimit] = useState(100);
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

  // 切换工作区 → 重新拉记录本清单，并清空右侧详情（避免展示上个工作区的残留条目）
  useEffect(() => {
    if (!wsId) return;
    setActiveKey('');
    setEntries([]);
    setLoading(true);
    getJson<BlackboardBook[]>(`/api/blackboard/books?workspaceId=${encodeURIComponent(wsId)}`)
      .then((list) => {
        setBooks(list);
        setError('');
        if (list.length > 0) setActiveKey(list[0].key);
      })
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false));
  }, [wsId]);

  // 选中记录本 / 调整条数上限 → 拉条目
  useEffect(() => {
    if (!wsId || !activeKey) return;
    setLoading(true);
    const url = `/api/blackboard/entries?workspaceId=${encodeURIComponent(wsId)}`
      + `&key=${encodeURIComponent(activeKey)}&limit=${limit}`;
    getJson<BlackboardEntry[]>(url)
      .then((list) => {
        setEntries(list);
        setError('');
      })
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false));
  }, [wsId, activeKey, limit]);

  const refresh = () => {
    if (!wsId) return;
    setLoading(true);
    Promise.all([
      getJson<BlackboardBook[]>(`/api/blackboard/books?workspaceId=${encodeURIComponent(wsId)}`),
      activeKey
        ? getJson<BlackboardEntry[]>(
            `/api/blackboard/entries?workspaceId=${encodeURIComponent(wsId)}`
            + `&key=${encodeURIComponent(activeKey)}&limit=${limit}`)
        : Promise.resolve<BlackboardEntry[]>([]),
    ])
      .then(([bs, es]) => {
        setBooks(bs);
        setEntries(es);
        setError('');
      })
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false));
  };

  const kw = keyword.trim().toLowerCase();
  const shown = entries.filter((e) => {
    if (typeFilter !== 'all' && (e.type || 'note') !== typeFilter) return false;
    if (kw && !(e.content || '').toLowerCase().includes(kw)
        && !(e.author || '').toLowerCase().includes(kw)) return false;
    return true;
  });

  // 类型分布统计：基于已加载的全量条目（不受筛选影响），让用户看到总体构成
  const counts = entries.reduce<Record<string, number>>((acc, e) => {
    const t = e.type || 'note';
    acc[t] = (acc[t] || 0) + 1;
    return acc;
  }, {});

  return (
    <div className="page">
      <h1 className="page-title">🗒️ 共享记录本</h1>
      <p className="hint" style={{ marginTop: -8 }}>
        主 Agent 与并行子 Agent 的共享结论区，只读（仅 AI 可经 blackboard_append 工具写入）。
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
          <label>
            条数&nbsp;
            <select value={limit} onChange={(e) => setLimit(Number(e.target.value))}>
              <option value={30}>最近 30</option>
              <option value={100}>最近 100</option>
            </select>
          </label>
          <input
            placeholder="搜索内容 / 作者"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            style={{ flex: '1 1 200px', minWidth: 160 }}
          />
          <button className="btn small" onClick={refresh} disabled={loading}>
            {loading ? '加载中…' : '刷新'}
          </button>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
        <div className="card" style={{ flex: '0 0 280px' }}>
          <h3 style={{ marginTop: 0 }}>记录本（{books.length}）</h3>
          {books.length === 0 && !loading && (
            <div className="hint">该工作区还没有记录本。它会在 AI 首次调用 blackboard_append 时创建。</div>
          )}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {books.map((b) => (
              <button
                key={b.key}
                className={'btn small' + (b.key === activeKey ? ' primary' : '')}
                onClick={() => setActiveKey(b.key)}
                style={{ textAlign: 'left', flexDirection: 'column', alignItems: 'flex-start' }}
                title={b.key}
              >
                <span style={{ fontWeight: 600, wordBreak: 'break-all' }}>{b.key}</span>
                <span className="hint" style={{ fontSize: 11 }}>
                  {b.entries} 条 · {fmtTime(b.lastModified)}
                </span>
              </button>
            ))}
          </div>
        </div>

        <div className="card" style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 12 }}>
            {TYPE_FILTERS.map((t) => {
              const meta = TYPE_META[t];
              const n = t === 'all' ? entries.length : (counts[t] || 0);
              return (
                <button
                  key={t}
                  className={'btn small' + (typeFilter === t ? ' primary' : '')}
                  onClick={() => setTypeFilter(t)}
                >
                  {meta ? `${meta.icon} ${meta.label}` : '全部'} ({n})
                </button>
              );
            })}
          </div>

          {!activeKey && <div className="hint">请选择左侧的一个记录本。</div>}
          {activeKey && shown.length === 0 && !loading && (
            <div className="hint">没有匹配的条目。</div>
          )}

          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {shown.map((e) => {
              const meta = TYPE_META[e.type || 'note'] || TYPE_META.note;
              return (
                <div key={e.seq} className="card" style={{ margin: 0, padding: '10px 12px' }}>
                  <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 6 }}>
                    <span>{meta.icon}</span>
                    <span className={`badge ${meta.cls}`}>{meta.label}</span>
                    <span className="hint">#{e.seq}</span>
                    <span className="hint" style={{ fontWeight: 600 }}>{e.author}</span>
                    <span className="hint" style={{ marginLeft: 'auto' }}>{e.ts}</span>
                  </div>
                  <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{e.content}</div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}