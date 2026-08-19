package com.xinl.easyclaw.mcp.service;

import com.embabel.agent.api.tool.Tool;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * STDIO MCP 连接器。
 * <p>
 * 使用 Java MCP SDK 启动子进程 MCP Server，并把远端工具转换为 Embabel Tool。
 */
@Component
public class StdioMcpConnector implements McpConnector {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpConnector.class);

    private final McpSdkSupport mcpSdkSupport;

    public StdioMcpConnector(McpSdkSupport mcpSdkSupport) {
        this.mcpSdkSupport = mcpSdkSupport;
    }

    @Override
    public boolean supports(McpServiceEntity service) {
        return service != null && "STDIO".equalsIgnoreCase(service.resolveTransport());
    }

    @Override
    public void connect(McpServiceEntity service) {
        boolean ok = mcpSdkSupport.connect(service);
        log.info("STDIO MCP 连接结果: name={}, success={}", service.getName(), ok);
        if (!ok) {
            throw new IllegalStateException("STDIO MCP 连接失败: " + service.getName());
        }
    }

    @Override
    public void disconnect(Long serviceId) {
        mcpSdkSupport.disconnect(serviceId);
    }

    @Override
    public List<Tool> getTools(Long serviceId) {
        return mcpSdkSupport.getTools(serviceId).stream()
                .map(info -> mcpSdkSupport.toEmbabelTool(serviceId, info))
                .toList();
    }
}
