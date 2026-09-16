package com.xinl.easyclaw.hub.contract.org;

/**
 * 组织视图（含当前用户在该组织的角色）。
 */
public record OrgDto(Long id, String name, String slug, String role, String plan) {
}
