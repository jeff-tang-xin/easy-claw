package com.xinl.easyclaw.hub.contract.audit;

import java.time.LocalDateTime;

/**
 * 审计日志条目（只读视图）。
 */
public record AuditLogDto(
        Long id,
        String module,
        String action,
        Long actorUserId,
        String actorUsername,
        Long orgId,
        String targetType,
        String targetId,
        String result,
        String detail,
        String ip,
        String userAgent,
        LocalDateTime createdAt) {
}
