package com.xinl.easyclaw.hub.contract.db;

import java.time.Instant;

/**
 * 数据库连接授权视图（platformAdmin）：expired = valid_until < now（查询时计算）。
 */
public record DbConnectionGrantDto(
        Long id,
        Long connectionId,
        Long userId,
        String userName,
        Instant validFrom,
        Instant validUntil,
        Long grantedBy,
        Boolean expired) {
}
