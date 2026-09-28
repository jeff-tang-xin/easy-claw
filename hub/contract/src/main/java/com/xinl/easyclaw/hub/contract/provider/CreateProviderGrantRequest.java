package com.xinl.easyclaw.hub.contract.provider;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 创建 provider 授权：userId 必填；dailyLimit null = 不限流，非空须 ≥1；
 * expiresAt null = 永久，非空须晚于当前时间。
 */
public record CreateProviderGrantRequest(@NotNull Long userId, Integer dailyLimit, Instant expiresAt) {
}
