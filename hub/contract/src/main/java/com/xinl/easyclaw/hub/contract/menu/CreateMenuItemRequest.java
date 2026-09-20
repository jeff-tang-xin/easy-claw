package com.xinl.easyclaw.hub.contract.menu;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增菜单项请求。menuKey 同工作区内唯一，仅允许小写字母/数字/连字符/下划线（spoke 侧稳定标识）。
 * parentId 可空（空=顶层）；requiredPerm/visibleRoles 可空（服务端归一化为空串=不限制）。
 */
public record CreateMenuItemRequest(
        @Pattern(regexp = "[a-z0-9_-]+", message = "menuKey 只能包含小写字母/数字/连字符/下划线")
        @Size(max = 64) String menuKey,
        @NotBlank @Size(max = 128) String label,
        @Size(max = 64) String icon,
        @Size(max = 255) String path,
        Long parentId,
        @Size(max = 64) String requiredPerm,
        @Size(max = 255) String visibleRoles,
        Integer sortOrder,
        Boolean enabled) {
}
