package com.xinl.easyclaw.hub.contract.knowledge;

import java.time.LocalDateTime;

/**
 * 知识条目某一历史版本全文（只读回看）。
 */
public record KnowledgeHistoryVersionDto(
        Long version,
        String topic,
        String summary,
        String content,
        Long actorUserId,
        String actorUsername,
        LocalDateTime createdAt) {
}
