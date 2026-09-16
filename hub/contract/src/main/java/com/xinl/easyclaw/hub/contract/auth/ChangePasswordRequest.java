package com.xinl.easyclaw.hub.contract.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改密码：登录后提交（含首登强制改密场景）。
 */
public record ChangePasswordRequest(
        @NotBlank String oldPassword,
        @NotBlank @Size(min = 8, max = 100) String newPassword) {
}
