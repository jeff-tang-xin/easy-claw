package com.xinl.easyclaw.config;

import com.xinl.easyclaw.mcp.service.McpConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 配置初始化
 * <p>
 * 应用启动时可初始化 MCP 服务连接
 */
@Configuration
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    @Bean
    public CommandLineRunner initMcpServices(McpConnectionService mcpService) {
        return args -> {
            log.info("MCP 服务初始化完成，当前已连接服务数: {}",
                    mcpService.findConnectedServices().size());
        };
    }
}
