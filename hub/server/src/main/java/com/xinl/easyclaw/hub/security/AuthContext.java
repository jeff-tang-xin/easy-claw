package com.xinl.easyclaw.hub.security;

/**
 * 当前请求的认证上下文（由 JWT 过滤器从 access token 解析而来，无状态）。
 *
 * @param userId       当前用户 id（JWT sub）
 * @param username     当前用户名
 * @param currentOrgId 当前组织 id（JWT act_org，可空）
 */
public record AuthContext(Long userId, String username, Long currentOrgId) {
}
