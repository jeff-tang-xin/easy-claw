package com.xinl.easyclaw.mcp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * MCP 服务连接配置实体
 * <p>
 * 字段对齐 Claude Desktop 标准 mcpServers JSON 格式：
 * <pre>{@code
 * {
 *   "mcpServers": {
 *     "name": {
 *       "command": "npx",
 *       "args": ["-y", "..."],
 *       "env": {"KEY": "value"},
 *       "cwd": "/path",
 *       "url": "https://.../mcp",
 *       "headers": {"Authorization": "Bearer ..."}
 *     }
 *   }
 * }
 * }</pre>
 */
@Entity
@Table(name = "mcp_services")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 用户自定义的服务名（唯一标识，对应 mcpServers 的 key）
     */
    @Column(name = "name", unique = true, nullable = false, length = 64)
    private String name;

    /**
     * 用户填写的备注说明
     */
    @Column(name = "description", length = 500)
    private String description;

    // ====== 用户配置字段（对应标准 mcpServers JSON） ======

    /**
     * 传输类型：STDIO / STREAMABLE_HTTP / SSE
     * 未指定时根据 url / command 自动推断
     */
    @Column(name = "transport", length = 24)
    private String transport;

    /**
     * HTTP 传输时的 MCP 端点 URL（STREAMABLE_HTTP 或 SSE）
     */
    @Column(name = "url", length = 500)
    private String url;

    /**
     * STDIO 传输时的启动命令（如 npx、uvx、python）
     */
    @Column(name = "command", length = 255)
    private String command;

    /**
     * STDIO 传输时的命令行参数（JSON 数组字符串）
     */
    @Column(name = "args", columnDefinition = "TEXT")
    private String args;

    /**
     * STDIO 传输时的环境变量（JSON 对象字符串）
     */
    @Column(name = "env", columnDefinition = "TEXT")
    private String env;

    /**
     * STDIO 传输时的工作目录
     */
    @Column(name = "cwd", length = 500)
    private String cwd;

    /**
     * HTTP 请求头（JSON 对象字符串），替代原来的 authType/authConfig
     * 例：{"Authorization":"Bearer xxx","X-Tenant":"tenant1"}
     */
    @Column(name = "headers", columnDefinition = "TEXT")
    private String headers;

    /**
     * 请求超时（秒），null 使用默认
     */
    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds;

    /**
     * 初始化超时（秒），null 使用默认
     */
    @Column(name = "init_timeout_seconds")
    private Integer initTimeoutSeconds;

    /**
     * 桥接实现的详细配置（JSON）
     * <p>
     * 当 transport = HTTP_TOOL 时，此字段存 REST API 工具的完整定义：
     * <pre>{@code
     * {
     *   "method": "GET",
     *   "urlTemplate": "https://api.weather.com/{city}",
     *   "bodyMode": "json",
     *   "params": {
     *     "city": {"in": "path", "type": "string", "required": true, "description": "城市名"}
     *   }
     * }
     * }</pre>
     * <p>
     * 当 transport = STDIO / STREAMABLE_HTTP / SSE 时，此字段可空。
     */
    @Column(name = "implementation_config", columnDefinition = "TEXT")
    private String implementationConfig;

    // ====== 运行时状态字段（系统自动填充） ======

    @Column(name = "is_connected")
    private Boolean isConnected;

    /**
     * 服务端返回的 Implementation.name（连接后自动填充）
     */
    @Column(name = "server_name", length = 128)
    private String serverName;

    /**
     * 服务端返回的 Implementation.title
     */
    @Column(name = "server_title", length = 256)
    private String serverTitle;

    /**
     * 服务端返回的 Implementation.version
     */
    @Column(name = "server_version", length = 64)
    private String serverVersion;

    /**
     * 服务端返回的 instructions（人类可读描述）
     */
    @Column(name = "server_instructions", columnDefinition = "TEXT")
    private String serverInstructions;

    /**
     * 服务端 capabilities（JSON，连接后自动填充）
     */
    @Column(name = "capabilities", columnDefinition = "TEXT")
    private String capabilities;

    /**
     * 可用工具列表 (JSON)
     */
    @Column(name = "available_tools", columnDefinition = "TEXT")
    private String availableTools;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "last_connected")
    private Instant lastConnected;

    /**
     * 作用域：SYSTEM（内置只读）/ GLOBAL（所有 workspace 可见）/ WORKSPACE（单个 workspace 私有）
     */
    @Column(name = "scope", length = 16)
    private String scope;

    /**
     * WORKSPACE scope 时关联的 workspace ID
     */
    @Column(name = "workspace_id", length = 64)
    private String workspaceId;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (isConnected == null) {
            isConnected = false;
        }
        if (scope == null) {
            scope = "GLOBAL";
        }
    }

    /**
     * 自动推断传输类型：有 command → STDIO；有 implementationConfig → HTTP_TOOL；URL 以 /sse 结尾 → SSE；否则 → STREAMABLE_HTTP
     */
    public String resolveTransport() {
        if (transport != null && !transport.isBlank()) {
            return transport.toUpperCase();
        }
        if (implementationConfig != null && !implementationConfig.isBlank()) {
            return "HTTP_TOOL";
        }
        if (command != null && !command.isBlank()) {
            return "STDIO";
        }
        if (url != null && !url.isBlank()) {
            return url.toLowerCase().endsWith("/sse") ? "SSE" : "STREAMABLE_HTTP";
        }
        throw new IllegalStateException("无法推断传输类型：既无 command、url 也无 implementationConfig");
    }
}
