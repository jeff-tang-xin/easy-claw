package com.xinl.easyclaw.hub.contract.spoke;

/**
 * 下发给 spoke 的工具（GET /api/spoke/tools）：仅含平台总开关开启的条目，
 * {@code enabled} 为该组织生效态（平台 enabled AND 组织 enabled）。
 */
public record SpokeToolInfo(
        String toolKey,
        Boolean enabled) {
}
