package com.xinl.easyclaw.hub.contract.db;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增数据库连接目录项请求（platformAdmin）。
 * serverKey 留空时按名称自动生成（同 ops 口径）；dbType 限四类；
 * password 可空（未设置时 spoke 走当次手输）；orgId 必须为正且组织存在；projectId 可选标记。
 */
public record CreateDbConnectionRequest(
        @Size(max = 64) String serverKey,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "mysql|postgresql|sqlserver|oracle", message = "dbType 仅支持 mysql/postgresql/sqlserver/oracle") String dbType,
        @NotBlank @Size(max = 255) String host,
        @NotNull @Min(1) @Max(65535) Integer port,
        @NotBlank @Size(max = 128) String databaseName,
        @NotBlank @Size(max = 64) String username,
        @Size(max = 255) String description,
        Boolean readonlyHint,
        Integer sortOrder,
        Boolean enabled,
        @NotNull Long orgId,
        Long projectId,
        @Size(max = 256) String password) {
}
