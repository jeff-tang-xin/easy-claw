package com.xinl.easyclaw.hub.contract.menu;

import jakarta.validation.constraints.Size;

/**
 * 修改平台菜单项请求（仅 platformAdmin）：字段均可空（不传即不改）；menuKey 创建后不可改（服务端裁决）。
 */
public record UpdateMenuItemRequest(
        @Size(max = 128) String label,
        @Size(max = 64) String icon,
        @Size(max = 255) String path,
        @Size(max = 64) String requiredPerm,
        @Size(max = 255) String visibleRoles,
        Integer sortOrder,
        Boolean enabled) {
}
