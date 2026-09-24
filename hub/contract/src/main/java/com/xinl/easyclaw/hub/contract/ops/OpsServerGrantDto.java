package com.xinl.easyclaw.hub.contract.ops;

import java.time.Instant;

/**
 * 运维服务器用户授权视图（ops_server_grants 表，V18）：platformAdmin 管理的用户 × 服务器时效授权。
 * userName 为冗余展示字段（查 users 表回填）；expired = valid_until &lt; now（过期行保留作历史）。
 */
public record OpsServerGrantDto(
        Long id,
        Long serverId,
        Long userId,
        String userName,
        Instant validFrom,
        Instant validUntil,
        Long grantedBy,
        boolean expired) {
}
