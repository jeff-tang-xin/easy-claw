package com.xinl.easyclaw.hub.contract.tool;

/**
 * 平台工具目录项视图（只读，不可经 API 增删；仅平台总开关可改）：工具由 Java Seeder 对齐
 * web/api ToolRegistry 播种，组织只能决定「是否启用」。toolKey 对齐 web/api 工具名，全局唯一。
 */
public record PlatformToolDto(
        Long id,
        String toolKey,
        String displayName,
        String description,
        String toolGroup,
        Integer sortOrder,
        Boolean enabled) {
}
