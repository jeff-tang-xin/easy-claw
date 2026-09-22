package com.xinl.easyclaw.hub.contract.menu;

import jakarta.validation.constraints.NotNull;

/**
 * 设置组织菜单可见性请求体（PUT /api/orgs/{orgId}/menu-settings/{menuId}）。
 */
public record SetMenuVisibleRequest(@NotNull Boolean visible) {
}
