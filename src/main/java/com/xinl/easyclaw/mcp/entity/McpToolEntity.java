package com.xinl.easyclaw.mcp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * MCP 服务下的单个 Tool / Endpoint。
 * <p>
 * 一个 McpService 是"服务容器"（共享鉴权 headers），下面挂多个 McpTool，
 * 每个 McpTool 对应一个具体的 REST endpoint：
 * <pre>
 * McpService("GitHub")
 *   ├── headers: {"Authorization": "Bearer ghp_xxx"}
 *   ├── McpTool("list_issues")     GET  /repos/{owner}/{repo}/issues
 *   ├── McpTool("create_issue")    POST /repos/{owner}/{repo}/issues
 *   └── McpTool("search_code")     GET  /search/code
 * </pre>
 */
@Entity
@Table(name = "mcp_tools")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属服务 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    private McpServiceEntity service;

    /** Tool 唯一标识（在同一 service 下唯一），同时作为 ActionRegistry 的 actionId */
    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    /** 显示名（可选） */
    @Column(name = "display_name", length = 128)
    private String displayName;

    /** Tool 描述（给 LLM 和 GOAP 看的） */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * REST endpoint 配置（JSON）：
     * <pre>{@code
     * {
     *   "method": "GET",
     *   "url": "https://api.github.com/repos/{owner}/{repo}/issues",
     *   "bodyMode": "json",
     *   "timeout": 15,
     *   "params": {
     *     "owner":  {"in":"path", "type":"string", "required":true, "exposeToLlm":true},
     *     "repo":   {"in":"path", "type":"string", "required":true, "exposeToLlm":true},
     *     "state":  {"in":"query","type":"string", "defaultValue":"open", "exposeToLlm":false}
     *   },
     *   "preconditions": ["已认证"],
     *   "effects": ["列出仓库 Issue"]
     * }
     * }</pre>
     */
    @Column(name = "tool_config", columnDefinition = "TEXT")
    private String toolConfig;

    /** 是否启用（可单独禁用某个 tool） */
    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    /** 排序号 */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (enabled == null) enabled = true;
        if (sortOrder == null) sortOrder = 0;
    }
}
