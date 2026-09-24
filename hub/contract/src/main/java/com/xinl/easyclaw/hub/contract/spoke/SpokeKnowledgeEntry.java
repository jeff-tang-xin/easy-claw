package com.xinl.easyclaw.hub.contract.spoke;

/** spoke 工作区知识库条目正文（GET /api/spoke/knowledge/entry）。 */
public record SpokeKnowledgeEntry(
        String topic,
        String content) {
}
