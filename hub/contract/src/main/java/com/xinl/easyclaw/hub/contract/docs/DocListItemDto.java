package com.xinl.easyclaw.hub.contract.docs;

import java.time.LocalDateTime;

/**
 * 文档列表项：不含正文，供项目内文档列表使用。
 */
public record DocListItemDto(
        Long id,
        Long projectId,
        Long parentDocId,
        String title,
        String docType,
        String status,
        Long ownerUserId,
        String ownerUsername,
        Long assigneeUserId,
        String assigneeUsername,
        Long version,
        LocalDateTime updatedAt) {
}
