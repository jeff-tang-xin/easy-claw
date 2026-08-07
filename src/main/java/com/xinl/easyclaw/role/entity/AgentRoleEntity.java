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
}
