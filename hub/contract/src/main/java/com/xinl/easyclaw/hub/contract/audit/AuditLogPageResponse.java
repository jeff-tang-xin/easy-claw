package com.xinl.easyclaw.hub.contract.audit;

import java.util.List;

/**
 * 审计日志分页响应。page 从 0 开始，size 为每页条数。
 */
public record AuditLogPageResponse(List<AuditLogDto> items, long total, int page, int size) {
}
