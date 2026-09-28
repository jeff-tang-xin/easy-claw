package com.xinl.easyclaw.hub.contract.featureflag;

import jakarta.validation.constraints.NotNull;

/**
 * 设置组织功能开关启用态请求体（PUT /api/orgs/{orgId}/flag-settings/{flagId}）。
 */
public record SetFlagEnabledRequest(@NotNull Boolean enabled) {
}
