package com.xinl.easyclaw.ops.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 运维场景远程连接配置实体（ops_connections）。
 * <p>
 * 每个工作区可保存多条连接（不同服务器/账号），运行时同一工作区同时只有一条活跃连接。
 * <p>
 * <b>凭证安全</b>：密码 / 私钥 / 私钥口令三个字段只存 {@code LocalCryptoService}
 * AES-256-GCM 加密后的密文（本地密钥文件 {@code ~/.easyClaw/ops-crypto.key}），
 * 明文永不落库、永不通过任何接口回显（列表/详情只回 {@code hasPassword} 等布尔位）。
 */
@Entity
@Table(name = "ops_connections", indexes = {
    @Index(name = "idx_ops_conn_ws_name", columnList = "workspace_id, name", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpsConnectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    /** 连接名（工作区内唯一，供下拉选择展示） */
    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    @Builder.Default
    private Integer port = 22;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    /** 认证方式：password = 密码；key = 私钥 */
    @Column(name = "auth_type", nullable = false, length = 20)
    @Builder.Default
    private String authType = "password";

    /** AES-256-GCM 加密后的密码（authType=password 时使用） */
    @Column(name = "password_enc", columnDefinition = "TEXT")
    private String passwordEnc;

    /** AES-256-GCM 加密后的私钥 PEM 文本（authType=key 时使用） */
    @Column(name = "private_key_enc", columnDefinition = "TEXT")
    private String privateKeyEnc;

    /** AES-256-GCM 加密后的私钥口令（可空） */
    @Column(name = "key_passphrase_enc", columnDefinition = "TEXT")
    private String keyPassphraseEnc;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
