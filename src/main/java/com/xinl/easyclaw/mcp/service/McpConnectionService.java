package com.xinl.easyclaw.mcp.service;

import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import java.util.List;
import java.util.Optional;

/**
 * MCP 服务连接管理接口
 */
public interface McpConnectionService {

    McpServiceEntity create(McpServiceEntity service);

    McpServiceEntity update(Long id, McpServiceEntity service);

    void delete(Long id);

    Optional<McpServiceEntity> findById(Long id);

    Optional<McpServiceEntity> findByName(String name);

    List<McpServiceEntity> findAll();

    List<McpServiceEntity> findConnectedServices();

    /**
     * 返回所有已建立连接的 MCP 客户端（供 Agent Toolkit 注册）
     */
    List<McpClientWrapper> getConnectedWrappers();

    McpServiceEntity connect(Long id);

    McpServiceEntity disconnect(Long id);
}
