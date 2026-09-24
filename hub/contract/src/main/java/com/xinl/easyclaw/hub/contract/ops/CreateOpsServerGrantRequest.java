package com.xinl.easyclaw.hub.contract.ops;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 授权用户访问运维服务器请求（POST /api/platform/ops-servers/{id}/grants，仅 platformAdmin）：
 * 同 (serverId, userId) 已有授权则续期（更新 valid_from/valid_until/granted_by）。
 * validUntil 必须在未来（服务端裁决）。
 */
public record CreateOpsServerGrantRequest(
        @NotNull Long userId,
        @NotNull Instant validUntil) {
}
