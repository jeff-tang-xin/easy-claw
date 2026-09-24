package com.xinl.easyclaw.hub.contract.knowledge;

import java.time.LocalDateTime;

/**
 * 知识条目详情：含当前版正文。向量（embedding）只在 hub 内部存取，绝不进契约。
 */
public record KnowledgeItemDto(
        Long id,
        Long projectId,
        String topic,
        String summary,
        String content,
        Long version,
        String status,
        Long ownerUserId,
        String ownerUsername,
        Long updatedBy,
        String updatedByUsername,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String embeddingStatus,
        String source,
        String sourceWorkspaceId) {
}
