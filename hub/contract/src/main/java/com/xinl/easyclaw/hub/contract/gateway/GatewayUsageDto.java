package com.xinl.easyclaw.hub.contract.gateway;

import java.util.List;

/**
 * 网关用量聚合（近 days 天窗口）：总量 + 按模型分布。
 */
public record GatewayUsageDto(
        int days,
        long totalRequests,
        long successRequests,
        long promptTokens,
        long completionTokens,
        List<ModelUsage> byModel) {

    public record ModelUsage(String model, long requests, long promptTokens, long completionTokens) {
    }
}
