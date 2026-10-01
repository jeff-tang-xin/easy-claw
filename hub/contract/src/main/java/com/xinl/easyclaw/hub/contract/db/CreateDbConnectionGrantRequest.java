package com.xinl.easyclaw.hub.contract.db;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 授权用户访问数据库连接请求（platformAdmin）：validUntil 必须是未来时刻（服务端校验）。
 */
public record CreateDbConnectionGrantRequest(
        @NotNull Long userId,
        @NotNull Instant validUntil) {
}
