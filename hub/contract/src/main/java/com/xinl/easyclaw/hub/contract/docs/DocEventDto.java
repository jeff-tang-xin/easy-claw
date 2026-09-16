package com.xinl.easyclaw.hub.contract.docs;

import java.time.LocalDateTime;

/**
 * 文档历史版本项（doc_events 一行）：每次 create/update 追加，不可变。
 */
public record DocEventDto(
        Long id,
        Long docId,
        Long version,
        String title,
        String content,
        Long actorUserId,
        String actorUsername,
        LocalDateTime createdAt) {
}
