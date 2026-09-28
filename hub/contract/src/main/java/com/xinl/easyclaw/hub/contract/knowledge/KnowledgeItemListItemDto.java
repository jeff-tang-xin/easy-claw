package com.xinl.easyclaw.hub.contract.knowledge;

import java.time.LocalDateTime;

/**
 * 知识条目列表项：不含正文，供项目内知识库列表使用。updatedBy 为最近一次编辑者（首版等于 owner）。
 * source=workspace 时 updatedBy/ownerUserId 为 0（Agent 写入占位，无 hub 用户），来源工作区见 sourceWorkspaceId。
 */
public record KnowledgeItemListItemDto(
        Long id,
        Long projectId,
        String topic,
        String summary,
        Long version,
        String status,
        Long ownerUserId,
        String ownerUsername,
        Long updatedBy,
        String updatedByUsername,
        LocalDateTime updatedAt,
        String embeddingStatus,
        String source,
        String sourceWorkspaceId) {
}
