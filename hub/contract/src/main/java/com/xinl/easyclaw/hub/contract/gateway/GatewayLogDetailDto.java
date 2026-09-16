package com.xinl.easyclaw.hub.contract.gateway;

import java.time.LocalDateTime;

/**
 * 网关详单详情：在列表项基础上附完整请求/响应正文（SSE 为聚合内容；正文超长已被截断并标注）。
 */
public record GatewayLogDetailDto(
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
        String errorMessage,
        String requestBody,
        String responseBody,
        String clientIp) {
}
