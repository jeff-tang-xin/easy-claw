package com.xinl.easyclaw.role.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * AI 角色定义实体
 * <p>
 * 存储角色的名称、角色设定、目标、背景故事、温度参数、模型等配置
 */
@Entity
@Table(name = "agent_roles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", unique = true, nullable = false, length = 64)
    private String name;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Column(name = "role_desc", length = 500)
    private String role;

    @Column(name = "goal", length = 500)
    private String goal;

    @Column(name = "backstory", columnDefinition = "TEXT")
    private String backstory;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "model", length = 64)
    private String model;

    /**
     * 角色专属 API 基址（可选）。
     * <p>留空 = 沿用全局 provider 配置（推荐）。仅当该角色要走一个未在全局
     * providers 中登记的端点时才填写，填写后必须同时提供 {@link #apiKey}。
     */
    @Column(name = "base_url", length = 256)
    private String baseUrl;

    /**
     * 角色专属 API Key（可选，与 {@link #baseUrl} 成对使用）。
     * <p><b>安全说明</b>：本字段以明文存于本地 SQLite。Easy-Claw 是单机自用工具，
     * 数据库文件位于用户自己的机器上，威胁模型与服务端多租户不同；但仍不建议
     * 在共享机器上使用该字段——优先走全局 provider 配置。
     * <p>对外返回时由 API 层做掩码，不回传明文。
     */
    @Column(name = "api_key", length = 256)
    @com.fasterxml.jackson.annotation.JsonProperty(
            access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private String apiKey;

    @Column(name = "is_active")
    private Boolean active;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (active == null) {
            active = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * 是否已配置角色专属密钥（只读，供前端区分"未配置"与"已配置但不回传明文"）。
     * apiKey 本身是 WRITE_ONLY，前端拿不到明文，只能靠这个标志渲染占位符。
     */
    @jakarta.persistence.Transient
    @com.fasterxml.jackson.annotation.JsonProperty(
            access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
