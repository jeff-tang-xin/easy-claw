package com.xinl.easyclaw.hub.contract.menu;

import java.time.LocalDateTime;

/**
 * 菜单项视图（管理面，扁平结构）：下发给 spoke 的公共菜单配置项，绑定工作区、与组织无关。
 * menuKey 为 spoke 侧稳定标识（同工作区内唯一）；requiredPerm 空=不要求权限码；
 * visibleRoles 逗号分隔角色、空=全部角色可见。
 */
public record MenuItemDto(
        Long id,
        Long workspaceId,
        Long parentId,
        String menuKey,
        String label,
        String icon,
        String path,
        String requiredPerm,
        String visibleRoles,
        Integer sortOrder,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
