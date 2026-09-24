package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 运维服务器目录（ops_servers，V17）：hub 统一维护（platformAdmin CRUD），spoke 只读消费。
 * serverKey 全局唯一、创建后不可改（spoke 侧稳定标识）。
 * V19 起可存运维登录密码密文（AES-256-GCM，CryptoService，与 llm_providers 同机制），
 * 随目录下发 spoke 供临时运维直连；未设置时 spoke 仍走本地录入凭证（向后兼容）。
 */
@Getter
@Setter
@Entity
@Table(name = "ops_servers",
        uniqueConstraints = @UniqueConstraint(name = "uk_ops_server_key", columnNames = "server_key"))
public class OpsServerEntity extends BaseEntity {

    @Column(name = "server_key", nullable = false, length = 64)
    private String serverKey;

    @Column(nullable = false, length = 128)
    private String name = "";

    @Column(nullable = false, length = 255)
    private String host = "";

    @Column(nullable = false)
    private Integer port = 22;

    @Column(nullable = false, length = 64)
    private String username = "";

    @Column(nullable = false, length = 255)
    private String description = "";

    /** 系统类型（V25，如 CentOS 7.9 / Ubuntu 22.04）：选填，随目录下发 spoke 供 AI 提示词注入。 */
    @Column(name = "os_type", nullable = false, length = 64)
    private String osType = "";

    /** 分类/标签（V21）：平台管理属性，创建必选；历史行空串 = 未标注。 */
    @Column(nullable = false, length = 64)
    private String category = "";

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** 归属组织（V18）：0 = 未归属遗留行，不满足下发条件，spoke 永远看不到，须 platformAdmin 补全。 */
    @Column(name = "org_id", nullable = false)
    private Long orgId = 0L;

    /** 归属项目（V18）：0 = 未归属遗留行；org_id 冗余口径对齐 V11 workspaces（无外键，应用层保证一致）。 */
    @Column(name = "project_id", nullable = false)
    private Long projectId = 0L;

    /** 登录密码密文（V19，AES-256-GCM）；NULL = 未设置。仅用于下发 spoke 直连，绝不在平台回显。 */
    @Column(name = "password_enc", length = 1024)
    private String passwordEnc;
}
