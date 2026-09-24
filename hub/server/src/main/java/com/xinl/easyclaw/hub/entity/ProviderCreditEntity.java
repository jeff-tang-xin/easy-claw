package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Provider 积分流水（provider_credits 表）：每笔发放一行，消耗按 FIFO 分摊到行。
 * 周期积分（daily/monthly/yearly）由网关热路径惰性发放（period_key 防当期重复）；
 * temp 为管理员手动发放的临时积分（period_key 为 NULL，SQL 唯一约束对 NULL 不去重，
 * 故可多笔）。过期时间越早越先消耗；过期未耗尽部分作废（剩余计算按 expires_at 过滤）。
 * 公共字段 id/created_at/updated_at 见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "provider_credits", uniqueConstraints = {
        @UniqueConstraint(name = "uk_provider_credits_period",
                columnNames = {"grant_id", "period_type", "period_key"})})
public class ProviderCreditEntity extends BaseEntity {

    /** 所属授权行（provider+user 维度）；删除授权时由应用层级联清理。 */
    @Column(name = "grant_id", nullable = false)
    private Long grantId;

    /** daily | monthly | yearly | temp。 */
    @Column(name = "period_type", nullable = false, length = 10)
    private String periodType;

    /** 当期标识（daily=2026-09-24 / monthly=2026-09 / yearly=2026）；temp 行为 NULL。 */
    @Column(name = "period_key", length = 20)
    private String periodKey;

    /** 本笔发放面额（支持 1 位小数，多余位数舍弃不进位）。 */
    @Column(name = "credits", nullable = false, precision = 12, scale = 1)
    private BigDecimal credits;

    /** 本笔已消耗（FIFO 分摊，原子扣减防超发）。 */
    @Column(name = "consumed", nullable = false, precision = 12, scale = 1)
    private BigDecimal consumed = BigDecimal.ZERO;

    /** 本笔积分有效期至（必填）；过期后剩余面额作废。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 发放操作人；周期惰性发放为 NULL（系统发放）。 */
    @Column(name = "created_by")
    private Long createdBy;
}
