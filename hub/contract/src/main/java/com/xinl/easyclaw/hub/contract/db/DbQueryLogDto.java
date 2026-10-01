package com.xinl.easyclaw.hub.contract.db;

import java.time.Instant;
import java.util.List;

/**
 * 数据库查询审计记录视图（platformAdmin 分页查询）：sqlText/serverKey 等为上报时快照。
 */
public record DbQueryLogDto(
        Long id,
        String serverKey,
        String serverName,
        String dbType,
        String host,
        String databaseName,
        String sqlText,
        String source,
        String operator,
        Instant executedAt) {

    /** 分页响应：content + 总数（口径同 OpsCommandLogPageResponse）。 */
    public record PageResponse(List<DbQueryLogDto> content, long totalElements, int page, int size) {
    }
}
