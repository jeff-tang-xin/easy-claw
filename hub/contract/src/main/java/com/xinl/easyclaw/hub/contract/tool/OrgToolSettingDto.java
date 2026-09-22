package com.xinl.easyclaw.hub.contract.tool;

/**
 * 组织工具启用行：全量平台目录 × 该组织生效态（无开关行 = 默认启用）。
 */
public record OrgToolSettingDto(
        Long toolId,
        String toolKey,
        String displayName,
        Boolean enabled) {
}
