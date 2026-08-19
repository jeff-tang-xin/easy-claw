import {useEffect, useState} from 'react';
import {getJson, del} from '../api';

interface Proposition {
  id: number;
  userId: string;
  workspaceId?: string;
  content: string;
  type: string;
  confidence: number | null;
  emotionTagsJson: string | null;
  topicsJson: string | null;
  conversationId: string | null;
  referenceCount: number | null;
  lastAccessed: string | null;
  createdAt: string | null;
}

interface WorkspaceRef {
  workspaceId: string;
  name: string;
}

const TYPE_LABEL: Record<string, string> = {
  PREFERENCE: '偏好',
  INTENT: '意图',
  FACT: '事实',
};

const TYPE_COLOR: Record<string, string> = {
  PREFERENCE: '#8b5cf6',
  INTENT: '#f59e0b',
  FACT: '#10b981',
};

export default function MemoriesPage() {
  const [items, setItems] = useState<Proposition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [filterType, setFilterType] = useState<string>('');
  const [workspaces, setWorkspaces] = useState<WorkspaceRef[]>([]);
  const [workspaceId, setWorkspaceId] = useState('');
  const userId = 'local';

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const query = new URLSearchParams({ userId });
      if (workspaceId) query.set('workspaceId', workspaceId);
      const data = await getJson<Proposition[]>(`/api/memory?${query.toString()}`);
      setItems(data);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    getJson<WorkspaceRef[]>('/api/workspaces').then(setWorkspaces).catch(() => {});
  }, []);

  useEffect(() => { load(); }, [workspaceId]);

  const handleDelete = async (id: number) => {
    if (!confirm('确定删除这条记忆吗？')) return;
    try {
      await del(`/api/memory/${id}`);
      setItems((prev) => prev.filter((m) => m.id !== id));
    } catch (e) {
      alert('删除失败: ' + e);
    }
  };

  const filtered = items.filter((m) => {
    if (filterType && m.type !== filterType) return false;
    if (search.trim()) {
      const q = search.toLowerCase();
      return m.content.toLowerCase().includes(q);
    }
    return true;
  });

  const parseTags = (json: string | null): string[] => {
    if (!json) return [];
    try { return JSON.parse(json); } catch { return []; }
  };

  return (
    <div className="page memories-page">
      <div className="page-header">
        <h2>🧠 长期记忆</h2>
        <button className="btn small" onClick={load} disabled={loading}>🔄 刷新</button>
      </div>
      <p className="page-hint">
        从对话中自动提取的偏好、意图和事实。Agent 会在后续对话中自动召回相关记忆。
      </p>

      <div className="memories-toolbar">
        <div className="memories-toolbar">
          <select value={workspaceId} onChange={(e) => setWorkspaceId(e.target.value)}>
            <option value="">全部工作区</option>
            {workspaces.map((w) => (
              <option key={w.workspaceId} value={w.workspaceId}>{w.name}</option>
            ))}
          </select>
        <input
          className="search-input"
          placeholder="🔍 搜索记忆..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <select value={filterType} onChange={(e) => setFilterType(e.target.value)}>
          <option value="">全部类型</option>
          <option value="PREFERENCE">偏好</option>
          <option value="INTENT">意图</option>
          <option value="FACT">事实</option>
        </select>
        <span className="counter">共 {filtered.length} / {items.length} 条</span>
      </div>
        </div>

      {error && <div className="error-banner">{error}</div>}

      {loading ? (
        <div className="loading">加载中...</div>
      ) : filtered.length === 0 ? (
        <div className="empty-state">
          {items.length === 0
            ? '暂无记忆。与 AI 多对话几轮后，系统会自动提取偏好、意图和事实。'
            : '没有匹配的记忆'}
        </div>
      ) : (
        <div className="memories-list">
          {filtered.map((m) => {
            const typeColor = TYPE_COLOR[m.type] || '#6b7280';
            const typeLabel = TYPE_LABEL[m.type] || m.type;
            const topics = parseTags(m.topicsJson);
            const emotions = parseTags(m.emotionTagsJson);
            return (
              <div key={m.id} className="memory-card">
                <div className="memory-header">
                  <span
                    className="memory-type-tag"
                    style={{background: typeColor + '22', color: typeColor, borderColor: typeColor}}
                  >
                    {typeLabel}
                  </span>
                  {m.confidence != null && (
                    <span className="memory-confidence" title="置信度">
                      ⭐ {Math.round(m.confidence * 100)}%
                    </span>
                  )}
                  {m.referenceCount != null && m.referenceCount > 0 && (
                    <span className="memory-refs" title="被引用次数">
                      🔁 {m.referenceCount}
                    </span>
                  )}
                  <button
                    className="memory-delete"
                    onClick={() => handleDelete(m.id)}
                    title="删除"
                  >✕</button>
                </div>
                <div className="memory-content">{m.content}</div>
                {(topics.length > 0 || emotions.length > 0) && (
                  <div className="memory-tags">
                    {topics.map((t, i) => (
                      <span key={'t' + i} className="tag topic">#{t}</span>
                    ))}
                    {emotions.map((t, i) => (
                      <span key={'e' + i} className="tag emotion">{t}</span>
                    ))}
                  </div>
                )}
                <div className="memory-meta">
                  {m.lastAccessed && <span>最后访问: {new Date(m.lastAccessed).toLocaleString()}</span>}
                  {m.createdAt && <span>创建: {new Date(m.createdAt).toLocaleDateString()}</span>}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
