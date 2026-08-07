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
 * 存储外部 MCP 服务器的连接信息，支持动态注册与连接状态管理
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

    @Column(name = "name", unique = true, nullable = false, length = 64)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "sse_url", nullable = false, length = 500)
    private String sseUrl;

    /**
     * 传输协议: SSE / HTTP / STDIO（默认 SSE）
     */
    @Column(name = "transport", length = 16)
    private String transport;

    /**
     * STDIO 传输时的启动命令（如 npx）
     */
    @Column(name = "command", length = 255)
    private String command;

    /**
     * STDIO 传输时的命令行参数 (JSON 数组字符串)
     */
    @Column(name = "args", columnDefinition = "TEXT")
    private String args;

    /**
     * STDIO 传输时的环境变量 (JSON 对象字符串)
     */
    @Column(name = "env", columnDefinition = "TEXT")
    private String env;

    /**
     * 认证类型: NONE / API_KEY / BEARER
     */
    @Column(name = "auth_type", length = 32)
    private String authType;

    @Column(name = "auth_config", columnDefinition = "TEXT")
    private String authConfig;

    @Column(name = "is_connected")
    private Boolean isConnected;

    /**
     * 可用工具列表 (JSON)
     */
    @Column(name = "available_tools", columnDefinition = "TEXT")
    private String availableTools;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "last_connected")
    private Instant lastConnected;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (isConnected == null) {
            isConnected = false;
        }
    }
}
