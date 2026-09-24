package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * Provider 授权每日用量（provider_grant_usage 表）：(grant, 日期) 唯一，网关路由时原子自增。
 * 只为配置了 daily_limit 的授权行计数；不限流的授权不落计数行。
 * 公共字段 id/created_at/updated_at 见 {@link BaseEntity}。
 * 唯一约束同时声明在实体上：Flyway V26 与 ddl-auto 建表（测试环境）保持一致，
 * upsert 的 ON CONFLICT (grant_id, usage_date) 依赖该约束。
 */
@Getter
@Setter
@Entity
@Table(name = "provider_grant_usage", uniqueConstraints = @UniqueConstraint(
        name = "uk_provider_grant_usage_grant_date", columnNames = {"grant_id", "usage_date"}))
public class ProviderGrantUsageEntity extends BaseEntity {

    @Column(name = "grant_id", nullable = false)
    private Long grantId;

    /** 用量归属日期（按服务器本地时区的自然日）。 */
    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "request_count", nullable = false)
    private int requestCount;
}
