package com.xinl.easyclaw.hub.contract.spoke;

/**
 * 下发给 spoke 的功能开关（GET /api/spoke/feature-flags）：仅含平台总开关开启的条目，
 * {@code enabled} 为该组织生效态（平台 enabled AND 组织 enabled）。
 */
public record SpokeFlagInfo(
        String flagKey,
        String label,
        Boolean enabled) {
}
