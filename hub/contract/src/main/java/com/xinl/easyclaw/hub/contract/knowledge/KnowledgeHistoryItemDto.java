package com.xinl.easyclaw.hub.contract.knowledge;

import java.time.LocalDateTime;

/**
 * 知识条目历史版本列表项（knowledge_item_events 一行）：不含正文，供历史抽屉列表使用。
 */
public record KnowledgeHistoryItemDto(
        Long version,
        String topic,
        String summary,
        Long actorUserId,
        String actorUsername,
        LocalDateTime createdAt) {
}
