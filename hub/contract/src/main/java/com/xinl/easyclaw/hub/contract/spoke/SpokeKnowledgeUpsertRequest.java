package com.xinl.easyclaw.hub.contract.spoke;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * spoke 工作区知识库条目 upsert 请求（POST /api/spoke/knowledge/entries）：
 * V22 起统一落项目知识库 knowledge_items，(project_id, topic) 唯一，存在即整体覆盖（后写赢）。
 * projectId = spoke 本地工作区绑定的 hub 项目（cloud 模式创建必绑），必填；
 * workspaceId 仅作来源留痕（source_workspace_id），不再参与唯一键。
 */
public record SpokeKnowledgeUpsertRequest(
        @NotBlank @Size(max = 64) String workspaceId,
        @NotNull Long projectId,
        @NotBlank @Size(max = 128) String topic,
        @Size(max = 500) String summary,
        String content) {
}
