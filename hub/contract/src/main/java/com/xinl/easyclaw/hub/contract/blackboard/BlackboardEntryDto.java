package com.xinl.easyclaw.hub.contract.blackboard;

import java.time.LocalDateTime;

/**
 * 黑板报条目：追加型、团队共享，按项目归类。status 为 active|archived（归档是条目的状态标签）。
 */
public record BlackboardEntryDto(
        Long id,
        Long projectId,
        String content,
        Long authorUserId,
        String authorUsername,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}