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
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
