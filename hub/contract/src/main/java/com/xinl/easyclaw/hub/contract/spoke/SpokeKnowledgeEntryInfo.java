package com.xinl.easyclaw.hub.contract.spoke;

/** spoke 工作区知识库条目列表项（GET /api/spoke/knowledge/entries）：lastModified 为 epoch 毫秒，fileSize 为 content 字节数。 */
public record SpokeKnowledgeEntryInfo(
        String topic,
        String summary,
        Long lastModified,
        Long fileSize) {
}
