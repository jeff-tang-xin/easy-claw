package com.xinl.easyclaw.hub.contract.db;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 更新数据库连接目录项请求（platformAdmin）：字段全可空 = 不传不改；
 * serverKey 创建后不可改（请求体不含该字段）；password null = 保持原密码，空串 = 清除。
 */
public record UpdateDbConnectionRequest(
        @Size(max = 128) String name,
        @Pattern(regexp = "mysql|postgresql|sqlserver|oracle", message = "dbType 仅支持 mysql/postgresql/sqlserver/oracle") String dbType,
        @Size(max = 255) String host,
        @Min(1) @Max(65535) Integer port,
        @Size(max = 128) String databaseName,
        @Size(max = 64) String username,
        @Size(max = 255) String description,
        Boolean readonlyHint,
        Integer sortOrder,
        Boolean enabled,
        Long orgId,
        Long projectId,
        @Size(max = 256) String password) {
}
