package com.xinl.easyclaw.db.service;

import java.time.Instant;

/**
 * 报表列表元数据投影（V32）：不含 htmlContent 大字段——报表 HTML 可达数 MB，
 * 列表全量拉取会把面板首屏拖垮；sizeBytes 由 JPQL length() 现算（5MB 上限内 int 足够）。
 * 字段名与前端 {@code ReportMeta} interface 一一对应。
 */
public record DbReportMeta(Long id, String title, String serverName, String dbType,
                           String databaseName, Instant createdAt, int sizeBytes) {
}
