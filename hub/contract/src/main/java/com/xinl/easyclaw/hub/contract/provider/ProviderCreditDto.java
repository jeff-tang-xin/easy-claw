package com.xinl.easyclaw.hub.contract.provider;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 积分流水视图（管理端）：每笔发放一行。periodKey 为当期标识（daily=2026-09-24 /
 * monthly=2026-09 / yearly=2026），temp（临时积分）为 null。remaining = 面额 − 已消耗；
 * 已过期行的 remaining 不再计入可用余额（展示用，标注过期）。积分值均为 1 位小数。
 */
public record ProviderCreditDto(Long id, String periodType, String periodKey,
                                BigDecimal credits, BigDecimal consumed, BigDecimal remaining,
                                Instant expiresAt, Instant createdAt) {
}
