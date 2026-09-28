package com.xinl.easyclaw.hub.contract.menu;

/**
 * 组织菜单可见性行：全量平台目录 × 该组织生效态（无开关行 = 默认可见）。
 */
public record OrgMenuSettingDto(
        Long menuId,
        String menuKey,
        String label,
        Boolean visible) {
}
