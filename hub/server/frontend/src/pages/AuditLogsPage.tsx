import {useCallback, useEffect, useState} from 'react';
import {fetchAuditLogs} from '../api';
import type {AuditLogPage} from '../types';

interface Props {
  orgId: number | null;
  role: string | null;
}

const PAGE_SIZE = 20;

const MODULES = [
  {value: '', label: '全部模块'},
  {value: 'auth', label: 'auth（认证）'},
  {value: 'org', label: 'org（组织）'},
  {value: 'project', label: 'project（项目）'},
  {value: 'user', label: 'user（用户）'},
];

/** 审计日志：组织维度分页查询，最新在前（仅 owner/admin 可见，服务端强制校验）。 */
export default function AuditLogsPage({orgId, role}: Props) {
  const [module, setModule] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<AuditLogPage | null>(null);
  const [error, setError] = useState('');

  const allowed = role === 'owner' || role === 'admin';

  const reload = useCallback(async () => {
    if (orgId == null || !allowed) return;
    try {
      setData(await fetchAuditLogs(orgId, module, page, PAGE_SIZE));
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载审计日志失败');
    }
  }, [orgId, allowed, module, page]);

  useEffect(() => {
    void reload();
  }, [reload]);

  // 切组织后回到第一页，避免停留在超出范围的页码
  useEffect(() => {
    setPage(0);
  }, [orgId]);

  if (orgId == null) {
    return (
      <div className="page">
        <div className="empty-state">
          <h3>还没有组织</h3>
          <p>请先创建组织。</p>
        </div>
      </div>
    );
  }
  if (!allowed) {
    return (
      <div className="page">
        <div className="empty-state">
          <h3>无权限</h3>
          <p>审计日志仅 owner / admin 可见。</p>
        </div>
      </div>
    );
  }

  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>审计日志</h2>
          <p className="page-desc">组织内的关键操作留痕，最新在前。</p>
        </div>
      </div>

      <div className="filter-bar">
        <select
          value={module}
          onChange={(e) => {
            setModule(e.target.value);
            setPage(0);
          }}
        >
          {MODULES.map((m) => (
            <option key={m.value} value={m.value}>
              {m.label}
            </option>
          ))}
        </select>
        <button type="button" className="btn" onClick={() => void reload()}>
          刷新
        </button>
      </div>

      {error && <div className="form-error">{error}</div>}
      {data == null && !error && <div className="empty-hint">加载中…</div>}

      {data != null && (
        <div className="card">
          <table className="data-table">
            <thead>
              <tr>
                <th>时间</th>
                <th>模块</th>
                <th>动作</th>
                <th>操作者</th>
                <th>目标</th>
                <th>结果</th>
                <th>明细</th>
              </tr>
            </thead>
            <tbody>
              {data.items.length === 0 && (
                <tr>
                  <td colSpan={7} className="empty-hint">
                    暂无记录
                  </td>
                </tr>
              )}
              {data.items.map((it) => (
                <tr key={it.id}>
                  <td style={{whiteSpace: 'nowrap'}}>{it.createdAt ? new Date(it.createdAt).toLocaleString() : '—'}</td>
                  <td>
                    <span className={`badge mod-${it.module}`}>{it.module}</span>
                  </td>
                  <td>
                    <span className="audit-action">{it.action}</span>
                  </td>
                  <td>{it.actorUsername ?? (it.actorUserId != null ? `#${it.actorUserId}` : '—')}</td>
                  <td>{it.targetType ? `${it.targetType}#${it.targetId ?? ''}` : '—'}</td>
                  <td>
                    <span className={`badge ${it.result === 'success' ? 'audit-ok' : 'audit-fail'}`}>{it.result}</span>
                  </td>
                  <td>
                    <div className="cell-ellipsis" title={it.detail ?? ''}>
                      {it.detail ?? '—'}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="table-footer">
            <span>共 {data.total} 条</span>
            <div className="pager">
              <button type="button" className="btn btn-sm" disabled={page <= 0} onClick={() => setPage(page - 1)}>
                ← 上一页
              </button>
              <span>
                第 {page + 1} / {totalPages} 页
              </span>
              <button
                type="button"
                className="btn btn-sm"
                disabled={page + 1 >= totalPages}
                onClick={() => setPage(page + 1)}
              >
                下一页 →
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
