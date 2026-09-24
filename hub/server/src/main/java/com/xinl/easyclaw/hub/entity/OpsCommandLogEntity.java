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
 * 运维命令记录实体（ops_command_logs 表，V25）：追加型、不可变（只有 created_at，无 updated_at，
 * 故不继承 BaseEntity，同 {@link AuditLogEntity} 口径）。spoke 执行命令后批量上报落库，
 * 供 platformAdmin 按服务器审计查询；server_key/server_name/host 为上报时快照，
 * 服务器后续改名不回溯历史行。
 */
@Getter
@Setter
@Entity
@Table(name = "ops_command_logs", indexes = {
        @Index(name = "idx_ops_cmd_server", columnList = "org_id, server_key, executed_at")})
public class OpsCommandLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属组织（appkey 上下文；0 = 无组织归属）。 */
    @Column(name = "org_id", nullable = false)
    private Long orgId = 0L;

    /** 服务器稳定标识（spoke 侧快照，全局唯一）。 */
    @Column(name = "server_key", nullable = false, length = 64)
    private String serverKey;

    /** 服务器名称（上报时快照，便于列表直读免回查目录）。 */
    @Column(name = "server_name", nullable = false, length = 128)
    private String serverName = "";

    /** 主机地址（上报时快照）。 */
    @Column(nullable = false, length = 255)
    private String host = "";

    /** 执行的命令内容。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String command;

    /** 来源：ai（智能体执行）| user（用户手动执行）。 */
    @Column(nullable = false, length = 16)
    private String source;

    /** 操作者（spoke 侧 appkey 创建人 userId；空 = 未知）。 */
    @Column(nullable = false, length = 128)
    private String operator = "";

    /** 命令执行时间（spoke 侧时钟；上报解析失败回退落库时间）。 */
    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
