package com.xinl.easyclaw.interceptor.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 拦截器配置实体
 * <p>
 * 支持动态配置权限、审计、限流等拦截器，可通过页面实时编辑
 */
@Entity
@Table(name = "interceptor_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterceptorConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", unique = true, nullable = false, length = 64)
    private String name;

    /**
     * 类型: PERMISSION / AUDIT / RATE_LIMIT / CUSTOM
     */
    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "order_num")
    private Integer orderNum;

    @Column(name = "enabled")
    private Boolean enabled;

    /**
     * 配置项 (JSON)
     */
    @Column(name = "config", columnDefinition = "TEXT")
    private String config;

    /**
     * 触发条件 (SpEL 表达式)
     */
    @Column(name = "conditions", columnDefinition = "TEXT")
    private String conditions;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (enabled == null) {
            enabled = true;
        }
        if (orderNum == null) {
            orderNum = 0;
        }
    }
}
