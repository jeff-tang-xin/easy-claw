package com.xinl.easyclaw.hub.contract.gateway;

import java.time.LocalDateTime;

/**
 * 网关详单列表项（不含请求/响应正文，详情见 {@link GatewayLogDetailDto}）。
 */
public record GatewayLogListItemDto(
        Long id,
        LocalDateTime createdAt,
        Long appKeyId,
        String keyPrefix,
        Long userId,
        Long providerId,
        String providerSlug,
        String apiType,
        String model,
        boolean stream,
        String status,
        Integer httpStatus,
        Long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        String errorMessage) {
}
