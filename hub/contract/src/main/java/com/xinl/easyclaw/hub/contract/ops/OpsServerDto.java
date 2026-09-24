package com.xinl.easyclaw.hub.contract.ops;

/**
 * 运维服务器目录项视图（ops_servers 表）：hub 统一维护（platformAdmin CRUD），spoke 只读消费。
 * serverKey 为 spoke 侧稳定标识，全局唯一、创建后不可改（服务端裁决）。
 * 回显红线：绝不回显密码明文或密文，仅回 passwordSet 布尔位（V19：hub 可存密码随目录下发
 * spoke 供临时运维直连；未设置时 spoke 仍走本地录入凭证）。
 */
public record OpsServerDto(
        Long id,
        String serverKey,
        String name,
        String host,
        Integer port,
        String username,
        String description,
        String osType,
        String category,
        Integer sortOrder,
        Boolean enabled,
        Long orgId,
        Long projectId,
        Boolean passwordSet) {
}
