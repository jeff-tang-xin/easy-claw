package com.xinl.easyclaw.hub.contract.provider;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 积分使用记录（每次请求一条）：modelName 为路由后实际生效模型，cost 为本次消耗积分
 * （按模型比例，1 位小数）。按时间倒序分页返回。
 */
public record CreditUsageDto(Long id, Long providerId, String providerName, String providerSlug,
                             String modelName, BigDecimal cost, Instant createdAt) {
}
