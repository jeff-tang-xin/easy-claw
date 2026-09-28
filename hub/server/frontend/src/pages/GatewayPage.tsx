import {useCallback, useEffect, useState} from 'react';
import {fetchGatewayLogDetail, fetchGatewayLogs, fetchGatewayUsage} from '../api';
import Modal from '../components/Modal';
import type {GatewayLogDetailDto, GatewayLogPage, GatewayUsageDto} from '../types';

interface Props {
  orgId: number | null;
  role: string | null;
}

const PAGE_SIZE = 20;

/** LLM 网关：转发详单 + 用量聚合两个视图（组织维度，仅 owner/admin 可见，服务端强制校验）。 */
export default function GatewayPage({orgId, role}: Props) {
  const [tab, setTab] = useState<'logs' | 'usage'>('logs');
  const allowed = role === 'owner' || role === 'admin';

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
          <p>网关详单与用量仅 owner / admin 可见。</p>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>LLM 网关</h2>
          <p className="page-desc">经 hub 转发的模型请求详单与用量统计。</p>
        </div>
      </div>

      <div className="page-tabs">
        <button
          type="button"
          className={tab === 'logs' ? 'tab-btn active' : 'tab-btn'}
          onClick={() => setTab('logs')}
        >
          转发详单
        </button>
        <button
          type="button"
          className={tab === 'usage' ? 'tab-btn active' : 'tab-btn'}
          onClick={() => setTab('usage')}
        >
          用量统计
        </button>
      </div>

      {tab === 'logs' ? <LogsView orgId={orgId} /> : <UsageView orgId={orgId} />}
    </div>
  );
}

/** 尝试把正文 pretty-print 为 JSON；非 JSON（如 SSE 聚合）原样返回。 */
function prettyBody(body: string | null): string {
  if (!body) return '—';
  try {
    return JSON.stringify(JSON.parse(body), null, 2);
  } catch {
    return body;
  }
}

function formatTokens(n: number | null): string {
  return n == null ? '—' : n.toLocaleString();
}

// ============ 转发详单 ============

function LogsView({orgId}: {orgId: number}) {
  const [status, setStatus] = useState('');
  const [model, setModel] = useState('');
  const [keyPrefix, setKeyPrefix] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<GatewayLogPage | null>(null);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<GatewayLogDetailDto | null>(null);
  const [detailError, setDetailError] = useState('');

  const reload = useCallback(async () => {
    try {
      setData(
        await fetchGatewayLogs(
          orgId,
          {status: status || undefined, model: model.trim() || undefined, keyPrefix: keyPrefix.trim() || undefined},
          page,
          PAGE_SIZE,
        ),
      );
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载网关详单失败');
    }
  }, [orgId, status, model, keyPrefix, page]);

  useEffect(() => {
    void reload();
  }, [reload]);

  // 切组织后回到第一页并重置过滤
  useEffect(() => {
    setPage(0);
    setStatus('');
    setModel('');
    setKeyPrefix('');
  }, [orgId]);

  const openDetail = useCallback(
    async (id: number) => {
      setDetailError('');
      try {
        setDetail(await fetchGatewayLogDetail(orgId, id));
      } catch (err) {
        setDetailError(err instanceof Error ? err.message : '加载详单详情失败');
      }
    },
    [orgId],
  );

  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <>
      <div className="filter-bar">
        <select
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        >
          <option value="">全部状态</option>
          <option value="success">success</option>
          <option value="error">error</option>
        </select>
        <input
          type="text"
          placeholder="按模型过滤，如 gpt-4o"
          value={model}
          onChange={(e) => {
            setModel(e.target.value);
            setPage(0);
          }}
        />
        <input
          type="text"
          placeholder="按 Key 前缀过滤"
          value={keyPrefix}
          onChange={(e) => {
            setKeyPrefix(e.target.value);
            setPage(0);
          }}
        />
        <button type="button" className="btn" onClick={() => void reload()}>
          刷新
        </button>
      </div>

      {error && <div className="form-error">{error}</div>}
      {detailError && <div className="form-error">{detailError}</div>}
      {data == null && !error && <div className="empty-hint">加载中…</div>}

      {data != null && (
        <div className="card">
          <table className="data-table">
            <thead>
              <tr>
                <th>时间</th>
                <th>Key</th>
                <th>Provider</th>
                <th>模型</th>
                <th>状态</th>
                <th>HTTP</th>
                <th>耗时</th>
                <th>Tokens（入/出）</th>
                <th>错误</th>
              </tr>
            </thead>
            <tbody>
              {data.items.length === 0 && (
                <tr>
                  <td colSpan={9} className="empty-hint">
                    暂无记录
                  </td>
                </tr>
              )}
              {data.items.map((it) => (
                <tr key={it.id} className="row-clickable" onClick={() => void openDetail(it.id)}>
                  <td style={{whiteSpace: 'nowrap'}}>
                    {it.createdAt ? new Date(it.createdAt).toLocaleString() : '—'}
                  </td>
                  <td>
                    <code>{it.keyPrefix ?? '—'}</code>
                    {it.stream && (
                      <span className="badge badge-stream" title="流式请求">
                        流
                      </span>
                    )}
                  </td>
                  <td>{it.providerSlug ?? '—'}</td>
                  <td>{it.model ?? '—'}</td>
                  <td>
                    <span className={`badge ${it.status === 'success' ? 'audit-ok' : 'audit-fail'}`}>
                      {it.status}
                    </span>
                  </td>
                  <td>{it.httpStatus ?? '—'}</td>
                  <td>{it.latencyMs != null ? `${it.latencyMs} ms` : '—'}</td>
                  <td>
                    {formatTokens(it.promptTokens)} / {formatTokens(it.completionTokens)}
                  </td>
                  <td>
                    <div className="cell-ellipsis" title={it.errorMessage ?? ''}>
                      {it.errorMessage ?? '—'}
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

      {detail && (
        <Modal
          title={`详单 #${detail.id}`}
          subtitle={`${detail.model ?? '—'} · ${detail.providerSlug ?? '未路由'} · ${
            detail.createdAt ? new Date(detail.createdAt).toLocaleString() : ''
          }`}
          onClose={() => setDetail(null)}
          width={760}
        >
          <div className="detail-grid">
            <span className="detail-label">状态</span>
            <span>
              <span className={`badge ${detail.status === 'success' ? 'audit-ok' : 'audit-fail'}`}>
                {detail.status}
              </span>{' '}
              HTTP {detail.httpStatus ?? '—'}
            </span>
            <span className="detail-label">Key 前缀</span>
            <span>
              <code>{detail.keyPrefix ?? '—'}</code>
            </span>
            <span className="detail-label">协议类型</span>
            <span>{detail.apiType ?? '—'}</span>
            <span className="detail-label">流式</span>
            <span>{detail.stream ? '是' : '否'}</span>
            <span className="detail-label">耗时</span>
            <span>{detail.latencyMs != null ? `${detail.latencyMs} ms` : '—'}</span>
            <span className="detail-label">Tokens</span>
            <span>
              输入 {formatTokens(detail.promptTokens)} / 输出 {formatTokens(detail.completionTokens)}
            </span>
            <span className="detail-label">客户端 IP</span>
            <span>{detail.clientIp ?? '—'}</span>
            {detail.errorMessage && (
              <>
                <span className="detail-label">错误</span>
                <span className="detail-error">{detail.errorMessage}</span>
              </>
            )}
          </div>
          <h4 className="detail-section-title">请求正文</h4>
          <pre className="log-body">{prettyBody(detail.requestBody)}</pre>
          <h4 className="detail-section-title">响应正文{detail.stream ? '（SSE 聚合）' : ''}</h4>
          <pre className="log-body">{prettyBody(detail.responseBody)}</pre>
        </Modal>
      )}
    </>
  );
}

// ============ 用量统计 ============

const DAY_OPTIONS = [
  {value: 7, label: '近 7 天'},
  {value: 30, label: '近 30 天'},
  {value: 90, label: '近 90 天'},
];

function UsageView({orgId}: {orgId: number}) {
  const [days, setDays] = useState(7);
  const [data, setData] = useState<GatewayUsageDto | null>(null);
  const [error, setError] = useState('');

  const reload = useCallback(async () => {
    try {
      setData(await fetchGatewayUsage(orgId, days));
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载用量统计失败');
    }
  }, [orgId, days]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const successRate =
    data && data.totalRequests > 0 ? ((data.successRequests / data.totalRequests) * 100).toFixed(1) : null;

  return (
    <>
      <div className="filter-bar">
        <select value={days} onChange={(e) => setDays(Number(e.target.value))}>
          {DAY_OPTIONS.map((d) => (
            <option key={d.value} value={d.value}>
              {d.label}
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
        <>
          <div className="stat-cards">
            <div className="stat-card">
              <div className="stat-value">{data.totalRequests.toLocaleString()}</div>
              <div className="stat-label">总请求数</div>
            </div>
            <div className="stat-card">
              <div className="stat-value">{data.successRequests.toLocaleString()}</div>
              <div className="stat-label">成功请求{successRate != null ? `（${successRate}%）` : ''}</div>
            </div>
            <div className="stat-card">
              <div className="stat-value">{data.promptTokens.toLocaleString()}</div>
              <div className="stat-label">输入 Tokens</div>
            </div>
            <div className="stat-card">
              <div className="stat-value">{data.completionTokens.toLocaleString()}</div>
              <div className="stat-label">输出 Tokens</div>
            </div>
          </div>

          <div className="card">
            <table className="data-table">
              <thead>
                <tr>
                  <th>模型</th>
                  <th>请求数</th>
                  <th>输入 Tokens</th>
                  <th>输出 Tokens</th>
                </tr>
              </thead>
              <tbody>
                {data.byModel.length === 0 && (
                  <tr>
                    <td colSpan={4} className="empty-hint">
                      该时间窗口内暂无用量
                    </td>
                  </tr>
                )}
                {data.byModel.map((m) => (
                  <tr key={m.model}>
                    <td>{m.model}</td>
                    <td>{m.requests.toLocaleString()}</td>
                    <td>{m.promptTokens.toLocaleString()}</td>
                    <td>{m.completionTokens.toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </>
  );
}
