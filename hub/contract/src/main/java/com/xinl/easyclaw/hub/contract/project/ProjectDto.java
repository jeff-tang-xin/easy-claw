package com.xinl.easyclaw.hub.contract.project;

import java.time.LocalDateTime;

/**
 * 项目视图。
 */
public record ProjectDto(
        Long id,
        Long orgId,
        String slug,
        String name,
        String description,
        String visibility,
        Long ownerUserId,
        /** 创建者展示名（displayName 优先，回落 username），列表/空间头部直接渲染，避免裸 id */
        String ownerUsername,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
