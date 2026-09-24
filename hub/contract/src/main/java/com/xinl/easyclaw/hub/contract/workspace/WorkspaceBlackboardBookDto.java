package com.xinl.easyclaw.hub.contract.workspace;

/**
 * 项目空间页「工作区同步黑板」本清单项（GET /api/projects/{projectId}/workspace-blackboard）：
 * spoke 工作区经云同步写入 workspace_blackboard 的条目按 (workspaceId, bookKey) 分组聚合（只读）。
 * lastModified / archivedAt 为 epoch 毫秒（无则为 null）；归档本 bookKey 形如 {@code <key>.archived-<epochMillis>}。
 */
public record WorkspaceBlackboardBookDto(
        String workspaceId,
        String bookKey,
        Long entryCount,
        Long lastModified,
        boolean archived,
        Long archivedAt) {
}
