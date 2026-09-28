package com.xinl.easyclaw.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 网关详单实体（gateway_logs 表，设计 §4.2）：一次网关转发的完整留档。
 * 追加型、不可变（只有 created_at，无 updated_at，故不继承 BaseEntity——口径同 AuditLogEntity；
 * V6 建表即按此设计，生产 PG 表无 updated_at 列）。
 * 正文（request/response_body）由应用层截断 64KB 并标注；SSE 为聚合后的完整内容。
 */
@Getter
@Setter
@Entity
@Table(name = "gateway_logs")
public class GatewayLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    @Column(name = "app_key_id", nullable = false)
    private Long appKeyId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    /** appkey.created_by 快照（appkey 为组织级凭据，调用者归属其创建人）。 */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "key_prefix", length = 32)
    private String keyPrefix;

    @Column(name = "provider_id")
    private Long providerId;

    /** 冗余快照：provider 被删后仍可查。 */
    @Column(name = "provider_slug", length = 64)
    private String providerSlug;

    @Column(name = "api_type", length = 20)
    private String apiType;

    @Column(length = 128)
    private String model;

    @Column(nullable = false)
    private boolean stream;

    /** success | error（上游 4xx/5xx 与网关自身拒绝均记 error）。 */
    @Column(nullable = false, length = 20)
    private String status;

    /** 上游 HTTP 状态码；网关自身拒绝（未打上游）为 null。 */
    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "request_body", columnDefinition = "TEXT")
    private String requestBody;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "client_ip", length = 64)
    private String clientIp;
}
