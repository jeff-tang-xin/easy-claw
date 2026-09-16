package com.xinl.easyclaw.hub.contract.org;

import jakarta.validation.constraints.NotBlank;

/**
 * 添加组织成员请求（按用户名或邮箱定位用户）。
 */
public record AddMemberRequest(
        @NotBlank String usernameOrEmail,
        @NotBlank String role) {
}
