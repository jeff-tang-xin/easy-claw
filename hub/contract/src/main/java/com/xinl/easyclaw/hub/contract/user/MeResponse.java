package com.xinl.easyclaw.hub.contract.user;

import com.xinl.easyclaw.hub.contract.org.OrgDto;
import java.util.List;
import java.util.Set;

/**
 * {@code GET /api/me} 响应：当前用户 + 所属组织 + 当前组织 + 权限清单（供控制台菜单/模式渲染）。
 */
public record MeResponse(
        UserDto user,
        List<OrgDto> orgs,
        Long currentOrgId,
        Set<String> permissions) {
}
