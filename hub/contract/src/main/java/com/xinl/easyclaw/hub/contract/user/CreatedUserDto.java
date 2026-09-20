package com.xinl.easyclaw.hub.contract.user;

import java.time.Instant;

/**
 * 平台管理员创建用户 / 重置密码的响应：携带一次性明文密码，仅本次返回，服务端不再可查、不投递邮箱。
 * 接收方（管理员）须立即复制并线下告知用户；关闭后无法再次查看。
 */
public record CreatedUserDto(Long id, String username, String email, String displayName, String status,
                             boolean platformAdmin, boolean mustChangePassword,
                             Instant passwordExpiresAt, boolean passwordExpiringSoon,
                             String tempPassword) {

    /** 由用户视图 + 一次性明文密码组装。 */
    public static CreatedUserDto of(UserDto u, String tempPassword) {
        return new CreatedUserDto(u.id(), u.username(), u.email(), u.displayName(), u.status(),
                u.platformAdmin(), u.mustChangePassword(), u.passwordExpiresAt(), u.passwordExpiringSoon(),
                tempPassword);
    }
}
