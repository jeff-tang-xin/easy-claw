package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 组织级 appkey 实体（app_keys 表）：明文 key 仅创建时返回一次，库中只存 SHA-256 hash + 展示前缀。
 * 全库无外键：org_id/created_by 的引用一致性由应用层保证。公共字段见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "app_keys")
public class AppKeyEntity extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(nullable = false, length = 64)
    private String name;

    /** 展示用前缀（如 eck-a1b2c3d4），用于列表/审计定位，不具认证效力。 */
    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    /** SHA-256 hex（64 字符），绝不落明文。 */
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    /** active|revoked */
    @Column(nullable = false, length = 20)
    private String status = "active";

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** 网关转发校验时回写（后续阶段）。 */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * 逻辑模型别名 {@code hub_cloud} 的默认路由目标 provider；{@code null} = 未配置。
     * 非空必须指向 active 且本 appkey 已绑定的 provider（应用层校验）。
     */
    @Column(name = "cloud_provider_id")
    private Long cloudProviderId;

    /**
     * {@code hub_cloud} 路由目标的真实模型名；{@code cloudProviderId} 非空时必填，
     * 且必须在该 provider 声明的 models 清单内（应用层校验）；{@code null} provider 时固定空串。
     */
    @Column(name = "cloud_model_name", nullable = false, length = 64)
    private String cloudModelName = "";
}
