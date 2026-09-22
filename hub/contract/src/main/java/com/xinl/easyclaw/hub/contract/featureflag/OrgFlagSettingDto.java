package com.xinl.easyclaw.hub.contract.featureflag;

/**
 * 组织功能开关启用行：全量平台目录 × 该组织生效态（无开关行 = 默认启用）。
 */
public record OrgFlagSettingDto(
        Long flagId,
        String flagKey,
        String label,
        Boolean enabled) {
}
