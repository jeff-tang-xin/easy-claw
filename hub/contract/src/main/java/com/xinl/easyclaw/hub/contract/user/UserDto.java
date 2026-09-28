package com.xinl.easyclaw.hub.contract.user;

import java.time.Instant;

/**
 * 用户视图。platformAdmin=平台管理员（可管理用户）；mustChangePassword=首次登录须先改密（其余 API 暂不可用）。
 * passwordExpiresAt=当前密码到期时刻（改密后重新起算）；passwordExpiringSoon=已进入到期前提醒窗口。
 */
public record UserDto(Long id, String username, String email, String displayName, String status,
                      boolean platformAdmin, boolean mustChangePassword,
                      Instant passwordExpiresAt, boolean passwordExpiringSoon) {
}
