package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 数据库连接目录（db_connections，V30）：hub 统一维护（platformAdmin CRUD），spoke 只读消费。
 * serverKey 全局唯一、创建后不可改（spoke 侧稳定标识）。
 * dbType 限四类：mysql / postgresql / sqlserver / oracle（应用层校验）。
 * databaseName 为默认库（可空——MySQL/PG/SQLServer 可只连实例，查询用 schema.table 限定名；
 * Oracle 存 service_name 必填）；readonly_hint 提示「只读账号」
 * （物理防线，随目录下发注入提示词）。
 * passwordEnc AES-256-GCM（CryptoService，同 ops_servers V19 机制），仅下发 spoke 直连，
 * 平台端永不回显。
 */
@Getter
@Setter
@Entity
@Table(name = "db_connections",
        uniqueConstraints = @UniqueConstraint(name = "uk_db_connection_key", columnNames = "server_key"))
public class DbConnectionEntity extends BaseEntity {

    @Column(name = "server_key", nullable = false, length = 64)
    private String serverKey;

    @Column(nullable = false, length = 128)
    private String name = "";

    /** mysql / postgresql / sqlserver / oracle（应用层 Pattern 校验收口）。 */
    @Column(name = "db_type", nullable = false, length = 32)
    private String dbType = "";

    @Column(nullable = false, length = 255)
    private String host = "";

    @Column(nullable = false)
    private Integer port = 0;

    /** 默认库；Oracle 存 service_name。 */
    @Column(name = "database_name", nullable = false, length = 128)
    private String databaseName = "";

    @Column(nullable = false, length = 64)
    private String username = "";

    @Column(nullable = false, length = 255)
    private String description = "";

    /** 平台声明「该连接使用只读账号」的勾选标记；随目录下发，spoke 侧作为事实提示注入模型（物理防线仍是 DB 账号权限本身）。V32 起默认不勾——db_dml/db_ddl 已是合法工具，默认只读会系统性误导模型拒绝写操作。 */
    @Column(name = "readonly_hint", nullable = false)
    private Boolean readonlyHint = false;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** 归属组织：0 = 未归属遗留行，不满足下发条件，spoke 永远看不到，须 platformAdmin 补全。 */
    @Column(name = "org_id", nullable = false)
    private Long orgId = 0L;

    /** 连接密码密文（AES-256-GCM）；NULL = 未设置。仅用于下发 spoke 直连，绝不在平台回显。 */
    @Column(name = "password_enc", length = 1024)
    private String passwordEnc;
}
