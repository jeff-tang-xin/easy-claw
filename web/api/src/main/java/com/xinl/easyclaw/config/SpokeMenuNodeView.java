package com.xinl.easyclaw.config;

import java.util.List;

/**
 * hub 下发的菜单节点（cloud-config 配置下发用，S5 配置上收）。
 *
 * @param menuKey      菜单唯一键（前端路由/渲染标识）
 * @param label        显示名
 * @param icon         图标标识（可空）
 * @param path         前端路由路径；空 = 分组节点（仅聚合 children，不对应路由）
 * @param requiredPerm 展示所需权限（空 = 不限）
 * @param visibleRoles 可见角色清单（空 = 不限角色）
 * @param children     子菜单（分组节点的聚合面）
 */
public record SpokeMenuNodeView(String menuKey, String label, String icon, String path,
                                String requiredPerm, List<String> visibleRoles,
                                List<SpokeMenuNodeView> children) {
}
