package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Provider 授权（provider_grants 表）：按用户授权某个 provider，appkey 调用网关时按
 * 创建者（created_by）维度校验。授权可选每日调用次数上限（dailyLimit，NULL = 不限流）
 * 与有效期（expiresAt，NULL = 永久）。
 * 启用语义：provider 存在任意授权行 = 启用授权模式（仅清单内用户可用）；
 * 未配置任何授权行 = 不启用（保持开放），存量 appkey 不受影响。
 * 公共字段 id/created_at/updated_at 见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "provider_grants")
public class ProviderGrantEntity extends BaseEntity {

    /** 授权的 provider；删除 provider 时由应用层级联清理。 */
    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    /** 被授权用户（appkey 创建者维度）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 每日调用次数上限（按请求次数计，成功失败都算）；NULL = 不限流。 */
    @Column(name = "daily_limit")
    private Integer dailyLimit;

    /** 授权有效期至；NULL = 永久。过期后按未授权拒绝。 */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** 每日固定发放积分（当天有效，次日惰性重发；支持 1 位小数）；NULL = 不发每日积分。三列全空 = 积分池未启用。 */
    @Column(name = "daily_credits", precision = 12, scale = 1)
    private BigDecimal dailyCredits;

    /** 每月固定发放积分（当月有效）；NULL = 不发。 */
    @Column(name = "monthly_credits", precision = 12, scale = 1)
    private BigDecimal monthlyCredits;

    /** 每年固定发放积分（当年有效）；NULL = 不发。 */
    @Column(name = "yearly_credits", precision = 12, scale = 1)
    private BigDecimal yearlyCredits;

    /** 授权操作人。 */
    @Column(name = "created_by")
    private Long createdBy;
}
