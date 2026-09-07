package com.xinl.easyclaw.permission.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 工具权限规则（用户"永久允许"持久化）
 * <p>
 * 规则绑定 Workspace：workspace_id 为空的规则视为全局默认（兼容历史数据）。
 */
@Entity
@Table(name = "permission_rules", indexes = {
        @Index(name = "idx_permission_ws_tool", columnList = "workspace_id, tool_name")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", length = 100)
    private String workspaceId;

    @Column(name = "tool_name", nullable = false, length = 100)
    private String toolName;

    @Column(name = "behavior", length = 20)
    @Builder.Default
    private String behavior = "ALLOW";

    @Column(length = 50)
    private String source;

    @Column(name = "created_at")
    private Instant createdAt;
}
