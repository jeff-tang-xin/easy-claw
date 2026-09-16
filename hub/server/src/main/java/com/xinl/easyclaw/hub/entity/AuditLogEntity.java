package com.xinl.easyclaw.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import com.xinl.easyclaw.hub.common.AuditModule;

/**
 * 审计日志实体（audit_logs 表）：追加型、不可变（只有 created_at，无 updated_at，故不继承 BaseEntity）。
 * 按 module（模块）+ action（动作）维度区分，辅以 actor / org / target 定位「谁对什么做了什么」。
 */
@Getter
@Setter
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_module_created", columnList = "module, created_at"),
        @Index(name = "idx_audit_org_created", columnList = "org_id, created_at"),
        @Index(name = "idx_audit_actor_created", columnList = "actor_user_id, created_at"),
        @Index(name = "idx_audit_target", columnList = "target_type, target_id")})
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模块：auth|org|project|user... */
    @Column(nullable = false, length = 32)
    private String module;

    /** 动作：register|login|create_org|add_member|... */
    @Column(nullable = false, length = 48)
    private String action;

    /** 操作者用户 id（可空 = 系统/匿名/注册或登录失败尚无身份）。 */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** 操作者用户名（冗余，便于检索；非当前请求用户时由 detail 体现）。 */
    @Column(name = "actor_username", length = 64)
    private String actorUsername;

    /** 相关组织 id（平台级事件可空）。 */
    @Column(name = "org_id")
    private Long orgId;

    /** 目标类型：user|organization|project... */
    @Column(name = "target_type", length = 32)
    private String targetType;

    /** 目标 id（字符串以兼容各类型主键）。 */
    @Column(name = "target_id", length = 64)
    private String targetId;

    /** 结果：success|failure。 */
    @Column(nullable = false, length = 16)
    private String result = AuditModule.SUCCESS;

    /** 附加上下文（JSON 字符串，A0 用 TEXT，后续需服务端 JSON 查询可迁 jsonb）。 */
    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(length = 64)
    private String ip;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (result == null || result.isBlank()) {
            result = AuditModule.SUCCESS;
        }
    }
}
