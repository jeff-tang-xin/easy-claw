package com.xinl.easyclaw.hub.contract.workspace;

/**
 * 项目空间页「工作区同步黑板」某本条目（GET /api/projects/{projectId}/workspace-blackboard/entries?bookKey=）。
 * 同一 bookKey 可能存在于多个 spoke 工作区，按 workspaceId 分组返回。
 */
public record WorkspaceBlackboardEntryDto(
        String workspaceId,
        String bookKey,
        Long seq,
        String ts,
        String author,
        String entryType,
        String content) {
}
