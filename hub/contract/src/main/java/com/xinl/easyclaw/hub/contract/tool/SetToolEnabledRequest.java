package com.xinl.easyclaw.hub.contract.tool;

import jakarta.validation.constraints.NotNull;

/**
 * 设置平台工具总开关请求体（PUT /api/platform/tools/{id}/enabled）。
 */
public record SetToolEnabledRequest(@NotNull Boolean enabled) {
}
