package com.xinl.easyclaw.hub.contract.user;

/**
 * 用户视图。platformAdmin=平台管理员（可管理用户）；mustChangePassword=首次登录须先改密（其余 API 暂不可用）。
 */
public record UserDto(Long id, String username, String email, String displayName, String status,
                      boolean platformAdmin, boolean mustChangePassword) {
}
