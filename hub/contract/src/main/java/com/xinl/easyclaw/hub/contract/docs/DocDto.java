package com.xinl.easyclaw.hub.contract.docs;

import java.time.LocalDateTime;

/**
 * 文档详情：含当前版正文。
 */
public record DocDto(
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
        String content,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
