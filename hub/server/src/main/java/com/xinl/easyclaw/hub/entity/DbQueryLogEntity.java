package com.xinl.easyclaw.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 数据库查询审计实体（db_query_logs 表，V30）：追加型、不可变（只有 created_at，无 updated_at，
 * 故不继承 BaseEntity，同 {@link OpsCommandLogEntity} 口径）。spoke 执行 AI 查询后批量上报落库，
 * 供 platformAdmin 按连接审计查询；server_key/server_name/db_type/host/database_name/sql_text
 * 为上报时快照，连接后续改名不回溯历史行。
 */
@Getter
@Setter
@Entity
@Table(name = "db_query_logs", indexes = {
        @Index(name = "idx_db_query_server", columnList = "org_id, server_key, executed_at")})
public class DbQueryLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属组织（appkey 上下文；0 = 无组织归属）。 */
    @Column(name = "org_id", nullable = false)
    private Long orgId = 0L;

    /** 连接稳定标识（spoke 侧快照，全局唯一）。 */
    @Column(name = "server_key", nullable = false, length = 64)
    private String serverKey;

    /** 连接名称（上报时快照，便于列表直读免回查目录）。 */
    @Column(name = "server_name", nullable = false, length = 128)
    private String serverName = "";

    /** 数据库类型（mysql/postgresql/sqlserver/oracle，上报时快照）。 */
    @Column(name = "db_type", nullable = false, length = 32)
    private String dbType = "";

    /** 主机地址（上报时快照）。 */
    @Column(nullable = false, length = 255)
    private String host = "";

    /** 查询目标库（上报时快照）。 */
    @Column(name = "database_name", nullable = false, length = 128)
    private String databaseName = "";

    /** 执行的 SQL 内容。 */
    @Column(name = "sql_text", nullable = false, columnDefinition = "TEXT")
    private String sqlText;

    /** 来源：ai（智能体执行）| user（用户手动执行）。 */
    @Column(nullable = false, length = 16)
    private String source;

    /** 操作者（spoke 侧 appkey 创建人 userId；空 = 未知）。 */
    @Column(nullable = false, length = 128)
    private String operator = "";

    /** 查询执行时间（spoke 侧时钟；上报解析失败回退落库时间）。 */
    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
