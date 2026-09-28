package com.xinl.easyclaw.hub.contract.spoke;

import java.math.BigDecimal;

/**
 * 下发给 spoke 的积分视图（GET /api/spoke/credits，appkey 创建者维度，按 provider 一行）。
 * remaining = 积分池当前可用余额（Σ 未过期面额 − 已消耗；积分池未启用为 null，1 位小数）；
 * dailyLimit/usedToday 为 V26 每日次数限流口径（未限流为 null），与积分池叠加校验。
 */
public record SpokeCreditInfo(Long providerId, String providerName, String providerSlug,
                              BigDecimal remaining, Integer dailyLimit, Integer usedToday) {
}
