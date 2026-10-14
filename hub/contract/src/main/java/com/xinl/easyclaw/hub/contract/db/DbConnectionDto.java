package com.xinl.easyclaw.hub.contract.db;

import java.util.List;

/**
 * 数据库连接目录项视图（platformAdmin）：password 永不回显，只出 passwordSet 布尔位。
 * projectIds = 绑定的项目 id 集合（V31 N:N；空 = 未绑定任何项目，对所有项目可见）。
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
        List<Long> projectIds,
        Boolean passwordSet) {
}
