package com.xinl.easyclaw.hub.contract.project;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目视图。dbServerKeys/opsServerKeys = 该项目绑定的资源（V31 N:N，按 serverKey；
 * 空清单 = 未绑定任何该类资源）。
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
        List<String> dbServerKeys,
        List<String> opsServerKeys,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
