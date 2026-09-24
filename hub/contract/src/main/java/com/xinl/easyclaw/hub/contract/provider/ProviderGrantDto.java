package com.xinl.easyclaw.hub.contract.provider;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Provider 授权视图：授权给哪个用户（username 随行带出）、每日调用次数上限（null = 不限流）、
 * 有效期（null = 永久）、今日已用次数（未限流授权为 null）。
 * V27 积分池：dailyCredits/monthlyCredits/yearlyCredits 为周期发放计划（null = 不发放该周期）；
 * remainingCredits 为积分池当前可用余额（Σ 未过期面额 − 已消耗；积分池未启用为 null）。
 * 积分值均为 1 位小数。
 */
public record ProviderGrantDto(Long id, Long providerId, Long userId, String username,
                               Integer dailyLimit, Instant expiresAt, Integer usedToday,
                               BigDecimal dailyCredits, BigDecimal monthlyCredits, BigDecimal yearlyCredits,
                               BigDecimal remainingCredits) {
}
