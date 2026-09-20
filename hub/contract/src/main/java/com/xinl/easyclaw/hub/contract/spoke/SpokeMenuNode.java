package com.xinl.easyclaw.hub.contract.spoke;

import java.util.List;

/**
 * 下发给 spoke 的菜单树节点：由 {@code menu_items} 扁平记录按 parentId 组装，仅含 enabled 项。
 * <p>{@code requiredPerm} 空=不要求权限码；{@code visibleRoles} 逗号分隔角色、空=全部角色可见——
 * 二者由 spoke 结合本地登录用户自行裁剪可见性（hub 只下发配置，不做 per-spoke 控制）。
 */
public record SpokeMenuNode(
        String menuKey,
        String label,
        String icon,
        String path,
        String requiredPerm,
        String visibleRoles,
        List<SpokeMenuNode> children) {
}
