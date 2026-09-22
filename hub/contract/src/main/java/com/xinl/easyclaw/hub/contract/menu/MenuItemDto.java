package com.xinl.easyclaw.hub.contract.menu;

/**
 * 平台菜单目录项视图（管理面，扁平结构）：菜单为平台级内置目录，新增/修改/删除仅 platformAdmin 可做，
 * 组织只能决定「是否显示」。menuKey 为 spoke 侧稳定标识（全局唯一、创建后不可改）；
 * requiredPerm 空=不要求权限码；visibleRoles 逗号分隔角色、空=全部角色可见；enabled 为平台总开关。
 */
public record MenuItemDto(
        Long id,
        Long parentId,
        String menuKey,
        String label,
        String icon,
        String path,
        String requiredPerm,
        String visibleRoles,
        Integer sortOrder,
        Boolean enabled) {
}
