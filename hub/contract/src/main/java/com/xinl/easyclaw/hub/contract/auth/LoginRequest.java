package com.xinl.easyclaw.hub.contract.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求（按用户名或邮箱）。
 */
public record LoginRequest(
        @NotBlank String usernameOrEmail,
        @NotBlank String password) {
}
