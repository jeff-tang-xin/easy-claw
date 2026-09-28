package com.xinl.easyclaw.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 积分消耗记录实体（credit_consumptions 表）：追加型、不可变（只有 created_at，无 updated_at，
 * 故不继承 BaseEntity）——每次网关请求消耗积分时落一条（模型、消耗分值），供积分使用情况
 * 页面展示使用记录。发放侧流水在 {@link ProviderCreditEntity}（含 consumed 分摊计数）。
 */
@Getter
@Setter
@Entity
@Table(name = "credit_consumptions", indexes = {
        @Index(name = "idx_credit_consumptions_grant", columnList = "grant_id, id")})
public class CreditConsumptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 授权行 id（provider × appkey 创建者）。 */
    @Column(name = "grant_id", nullable = false)
    private Long grantId;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    /** 请求模型名（路由后实际生效模型）。 */
    @Column(name = "model_name", nullable = false, length = 255)
    private String modelName;

    /** 本次消耗积分（按模型比例，1 位小数）。 */
    @Column(nullable = false, precision = 12, scale = 1)
    private BigDecimal cost;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
