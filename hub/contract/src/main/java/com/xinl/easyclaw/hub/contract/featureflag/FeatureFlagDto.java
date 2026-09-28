package com.xinl.easyclaw.hub.contract.featureflag;

/**
 * 平台功能开关目录项视图：开关为平台级内置目录，新增/修改/删除仅 platformAdmin 可做，
 * 组织只能决定「是否启用」。flagKey 全局唯一、创建后不可改；enabled 为平台总开关。
 */
public record FeatureFlagDto(
        Long id,
        String flagKey,
        String label,
        String description,
        Boolean enabled,
        Integer sortOrder) {
}
