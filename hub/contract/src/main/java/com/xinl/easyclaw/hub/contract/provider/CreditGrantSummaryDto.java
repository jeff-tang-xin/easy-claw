package com.xinl.easyclaw.hub.contract.provider;

import java.math.BigDecimal;

/**
 * 积分总览行（管理员视角，每条授权一行）：provider × 用户维度。
 * remaining = 积分池总剩余；四项构成同 {@link CreditBalanceDto}（未启用积分池全 null）。
 * 积分值均为 1 位小数。
 */
public record CreditGrantSummaryDto(Long grantId, Long providerId, String providerName, String providerSlug,
                                    Long userId, String username, BigDecimal remaining,
                                    BigDecimal dailyRemaining, BigDecimal monthlyRemaining,
                                    BigDecimal yearlyRemaining, BigDecimal tempRemaining,
                                    Integer dailyLimit, Integer usedToday) {
}
