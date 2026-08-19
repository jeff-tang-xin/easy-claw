package com.xinl.easyclaw.mcp.service;

import com.embabel.agent.api.tool.Tool;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;

import java.util.List;

/**
 * MCP 连接器 SPI。
 * <p>
 * 每种传输协议一个实现：
 * <ul>
 *   <li>HTTP_TOOL：REST 桥接（当前已实现）</li>
 *   <li>STDIO：子进程 MCP Server（待实现）</li>
 *   <li>SSE：SSE MCP Server（待实现）</li>
 *   <li>STREAMABLE_HTTP：Streamable HTTP MCP Server（待实现）</li>
 * </ul>
 */
public interface McpConnector {

    boolean supports(McpServiceEntity service);

    void connect(McpServiceEntity service);

    void disconnect(Long serviceId);

    List<Tool> getTools(Long serviceId);
}
