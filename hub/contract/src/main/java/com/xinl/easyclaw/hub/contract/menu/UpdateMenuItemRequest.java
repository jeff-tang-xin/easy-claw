package com.xinl.easyclaw.hub.contract.menu;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改菜单项请求：字段均可空（不传即不改）；menuKey 若要改须符合规则且同工作区内不与其他项冲突。
 */
public record UpdateMenuItemRequest(
        @Pattern(regexp = "[a-z0-9_-]+", message = "menuKey 只能包含小写字母/数字/连字符/下划线")
        @Size(max = 64) String menuKey,
        @Size(max = 128) String label,
        @Size(max = 64) String icon,
        @Size(max = 255) String path,
        @Size(max = 64) String requiredPerm,
        @Size(max = 255) String visibleRoles,
        Integer sortOrder,
        Boolean enabled) {
}
