package com.xinl.easyclaw.hub.contract.db;

/**
 * 数据库连接目录项视图（platformAdmin）：password 永不回显，只出 passwordSet 布尔位。
 */
public record DbConnectionDto(
        Long id,
        String serverKey,
        String name,
        String dbType,
        String host,
        Integer port,
        String databaseName,
        String username,
        String description,
        Boolean readonlyHint,
        Integer sortOrder,
        Boolean enabled,
        Long orgId,
        Long projectId,
        Boolean passwordSet) {
}
