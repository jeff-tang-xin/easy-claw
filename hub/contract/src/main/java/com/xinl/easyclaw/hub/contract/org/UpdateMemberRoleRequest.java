package com.xinl.easyclaw.hub.contract.org;

import jakarta.validation.constraints.NotBlank;

/**
 * 修改成员角色请求。
 */
public record UpdateMemberRoleRequest(@NotBlank String role) {
}
