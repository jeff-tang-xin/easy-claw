package com.xinl.easyclaw.hub.contract.blackboard;

import java.time.LocalDateTime;

/**
 * 黑板报条目：追加型、团队共享，按项目归类。status 为 active|archived（归档是条目的状态标签）。
 * V24 起含 spoke 同步条目：source=workspace 时 authorUserId=0（Agent 占位）、entryType 非空。
 */
public record BlackboardEntryDto(
        Long id,
        Long projectId,
        String content,
        Long authorUserId,
        String authorUsername,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String source,
        String sourceWorkspaceId,
        String entryType) {
}