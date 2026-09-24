package com.xinl.easyclaw.hub.contract.provider;

import java.math.BigDecimal;

/**
 * 积分余额视图（按 provider 一行）：remaining = 积分池总剩余（Σ 未过期面额 − 已消耗；
 * null = 未启用积分池）。daily/monthly/yearly/temp 四项为积分构成（各周期未过期剩余，
 * 未启用积分池时全 null）。dailyLimit/usedToday 为每日次数限流口径（未限流为 null）。
 * 积分值均为 1 位小数。
 */
public record CreditBalanceDto(Long providerId, String providerName, String providerSlug,
                               BigDecimal remaining,
                               BigDecimal dailyRemaining, BigDecimal monthlyRemaining,
                               BigDecimal yearlyRemaining, BigDecimal tempRemaining,
                               Integer dailyLimit, Integer usedToday) {
}
