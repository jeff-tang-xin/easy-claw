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
 * databaseName 为默认库（Oracle 存 service_name）；readonly_hint 提示「必须只读账号」
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

    /** 录入时提示「必须只读账号」的勾选标记；随目录下发注入提示词（物理防线提醒）。 */
    @Column(name = "readonly_hint", nullable = false)
    private Boolean readonlyHint = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** 归属组织：0 = 未归属遗留行，不满足下发条件，spoke 永远看不到，须 platformAdmin 补全。 */
    @Column(name = "org_id", nullable = false)
    private Long orgId = 0L;

    /** 归属项目：0 = 不限定项目（org_id 冗余口径对齐 ops_servers，无外键，应用层保证一致）。 */
    @Column(name = "project_id", nullable = false)
    private Long projectId = 0L;

    /** 连接密码密文（AES-256-GCM）；NULL = 未设置。仅用于下发 spoke 直连，绝不在平台回显。 */
    @Column(name = "password_enc", length = 1024)
    private String passwordEnc;
}
