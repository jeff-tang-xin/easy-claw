package com.xinl.easyclaw.hub.contract.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新令牌请求。
 */
public record RefreshRequest(@NotBlank String refreshToken) {
}
