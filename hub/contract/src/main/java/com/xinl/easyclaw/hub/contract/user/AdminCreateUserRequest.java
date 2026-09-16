package com.xinl.easyclaw.hub.contract.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 平台管理员创建用户：临时密码由服务端生成并投递邮箱，首登强制改密。
 * orgId 给了则同时加入该组织（role 省略默认 member；可取 member|admin|guest，不能直接任命 owner）。
 */
public record AdminCreateUserRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @NotBlank @Email @Size(max = 128) String email,
        @Size(max = 64) String displayName,
        Long orgId,
        @Size(max = 20) String role) {
}
