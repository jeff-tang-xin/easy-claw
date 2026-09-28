import {useCallback, useEffect, useState} from 'react';
import {listMyCreditUsages, listMyCredits, listOrgCreditOverview, listPlatformCreditOverview} from '../api';
import {loadSession} from '../auth';
import type {CreditBalanceDto, CreditGrantSummaryDto, CreditUsageDto, MeResponse} from '../types';

const fmt = (v: number | null | undefined) => (v === null || v === undefined ? '—' : String(v));
const fmtTime = (s: string) => new Date(s).toLocaleString('zh-CN', {hour12: false});

/** 积分构成徽标：每日/每月/每年/临时四部分剩余（0 值也展示，让构成一目了然）。 */
function CompositionBadges({b}: {b: CreditBalanceDto | CreditGrantSummaryDto}) {
  const items: [string, number | null][] = [
    ['每日', b.dailyRemaining],
    ['每月', b.monthlyRemaining],
    ['每年', b.yearlyRemaining],
    ['临时', b.tempRemaining],
  ];
  return (
    <span style={{display: 'inline-flex', gap: 4, flexWrap: 'wrap'}}>
      {items.map(([label, v]) => (
        <span key={label} className="badge role-member" title={`${label}积分剩余`}>
          {label} {fmt(v)}
        </span>
      ))}
    </span>
  );
}

/**
 * 积分使用情况页：我的积分（余额 + 每日/每月/每年/临时构成 + 使用记录）；
 * 组织 owner/admin 可看全组织总览，platformAdmin 额外可看平台共享池。
 * 扣减口径：按请求模型的目录比例扣分（未登记模型 1 分/次），FIFO 按过期时间消耗。
 */
export default function CreditsPage({me}: {me: MeResponse}) {
  const canManageOrg = me.permissions.includes('provider.manage');
  const isPlatformAdmin = me.permissions.includes('platform.catalog.manage');

  // ============ 我的积分 ============
  const [balances, setBalances] = useState<CreditBalanceDto[] | null>(null);
  const [usages, setUsages] = useState<CreditUsageDto[] | null>(null);
  const [usagePage, setUsagePage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [myError, setMyError] = useState('');

  const reloadMine = useCallback(async () => {
    setMyError('');
    try {
      const [b, u] = await Promise.all([listMyCredits(), listMyCreditUsages(0, 20)]);
      setBalances(b);
      setUsages(u);
      setUsagePage(0);
      setHasMore(u.length === 20);
    } catch (err) {
      setMyError(err instanceof Error ? err.message : '加载积分数据失败');
    }
  }, []);

  const loadMoreUsages = async () => {
    if (!usages) return;
    const next = usagePage + 1;
    try {
      const more = await listMyCreditUsages(next, 20);
      setUsages([...usages, ...more]);
      setUsagePage(next);
      setHasMore(more.length === 20);
    } catch (err) {
      setMyError(err instanceof Error ? err.message : '加载使用记录失败');
    }
  };

  useEffect(() => {
    void reloadMine();
  }, [reloadMine]);

  // ============ 组织/平台总览（管理员） ============
  const [overviewScope, setOverviewScope] = useState<'platform' | number | ''>('');
  const [overview, setOverview] = useState<CreditGrantSummaryDto[] | null>(null);
  const [overviewError, setOverviewError] = useState('');

  const loadOverview = useCallback(async (scope: 'platform' | number | '') => {
    if (scope === '') {
      setOverview(null);
      return;
    }
    setOverviewError('');
    setOverview(null);
    try {
      const rows =
        scope === 'platform' ? await listPlatformCreditOverview() : await listOrgCreditOverview(scope);
      setOverview(rows);
    } catch (err) {
      setOverviewError(err instanceof Error ? err.message : '加载总览失败');
    }
  }, []);

  useEffect(() => {
    void loadOverview(overviewScope);
  }, [overviewScope, loadOverview]);

  // 组织下拉：我所在的组织（owner/admin 才能调通总览端点；member 选项隐藏）
  const manageableOrgs = me.orgs.filter(() => canManageOrg);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>积分使用情况</h2>
          <p className="page-desc">
            按请求模型的积分比例扣减（未登记模型 1 分/次）；积分池按过期时间先后 FIFO 消耗，
            过期未耗尽部分作废。剩余 = Σ 未过期面额 − 已消耗，由每日 / 每月 / 每年 / 临时四部分构成。
          </p>
        </div>
      </div>

      {myError && <div className="form-error">{myError}</div>}

      <div className="card">
        <h3 style={{marginTop: 0}}>我的积分余额</h3>
        {balances === null ? (
          <p className="page-desc">加载中…</p>
        ) : balances.length === 0 ? (
          <p className="page-desc">暂无积分数据（未被授权任何 Provider，或授权未启用积分池）。</p>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Provider</th>
                <th>剩余积分</th>
                <th>构成（每日 / 每月 / 每年 / 临时）</th>
                <th>每日次数</th>
              </tr>
            </thead>
            <tbody>
              {balances.map((b) => (
                <tr key={b.providerId}>
                  <td>
                    {b.providerName ?? `Provider #${b.providerId}`}
                    {b.providerSlug && (
                      <span className="page-desc" style={{marginLeft: 6, fontFamily: 'monospace'}}>
                        {b.providerSlug}
                      </span>
                    )}
                  </td>
                  <td>
                    {b.remaining === null ? (
                      <span className="page-desc">未启用积分池</span>
                    ) : (
                      <strong>⚡ {fmt(b.remaining)}</strong>
                    )}
                  </td>
                  <td>{b.remaining === null ? '—' : <CompositionBadges b={b} />}</td>
                  <td>
                    {b.dailyLimit === null
                      ? '不限'
                      : `${fmt(b.usedToday)} / ${b.dailyLimit}`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <h3 style={{marginTop: 0}}>我的使用记录</h3>
        {usages === null ? (
          <p className="page-desc">加载中…</p>
        ) : usages.length === 0 ? (
          <p className="page-desc">暂无消耗记录。</p>
        ) : (
          <>
            <table className="data-table">
              <thead>
                <tr>
                  <th>时间</th>
                  <th>Provider</th>
                  <th>模型</th>
                  <th>消耗积分</th>
                </tr>
              </thead>
              <tbody>
                {usages.map((u) => (
                  <tr key={u.id}>
                    <td>{fmtTime(u.createdAt)}</td>
                    <td>{u.providerName ?? `Provider #${u.providerId}`}</td>
                    <td style={{fontFamily: "ui-monospace, 'Cascadia Code', Consolas, monospace"}}>
                      {u.modelName || '—'}
                    </td>
                    <td>{fmt(u.cost)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {hasMore && (
              <button type="button" className="btn btn-ghost" onClick={() => void loadMoreUsages()}>
                加载更多
              </button>
            )}
          </>
        )}
      </div>

      {(canManageOrg || isPlatformAdmin) && (
        <div className="card">
          <h3 style={{marginTop: 0}}>积分总览（管理员）</h3>
          <div style={{marginBottom: 12}}>
            <select
              value={overviewScope}
              onChange={(e) => {
                const v = e.target.value;
                setOverviewScope(v === '' ? '' : v === 'platform' ? 'platform' : Number(v));
              }}
            >
              <option value="">选择查看范围…</option>
              {isPlatformAdmin && <option value="platform">平台共享池</option>}
              {manageableOrgs.map((o) => (
                <option key={o.id} value={o.id}>
                  {o.name}
                </option>
              ))}
            </select>
          </div>
          {overviewError && <div className="form-error">{overviewError}</div>}
          {overviewScope !== '' && overview === null && !overviewError && (
            <p className="page-desc">加载中…</p>
          )}
          {overview !== null &&
            (overview.length === 0 ? (
              <p className="page-desc">该范围内暂无授权行。</p>
            ) : (
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Provider</th>
                    <th>用户</th>
                    <th>剩余积分</th>
                    <th>构成（每日 / 每月 / 每年 / 临时）</th>
                    <th>每日次数</th>
                  </tr>
                </thead>
                <tbody>
                  {overview.map((g) => (
                    <tr key={g.grantId}>
                      <td>{g.providerName ?? `Provider #${g.providerId}`}</td>
                      <td>{g.username ?? `用户 #${g.userId}`}</td>
                      <td>
                        {g.remaining === null ? (
                          <span className="page-desc">未启用积分池</span>
                        ) : (
                          <strong>⚡ {fmt(g.remaining)}</strong>
                        )}
                      </td>
                      <td>{g.remaining === null ? '—' : <CompositionBadges b={g} />}</td>
                      <td>{g.dailyLimit === null ? '不限' : `${fmt(g.usedToday)} / ${g.dailyLimit}`}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ))}
        </div>
      )}
    </div>
  );
}
