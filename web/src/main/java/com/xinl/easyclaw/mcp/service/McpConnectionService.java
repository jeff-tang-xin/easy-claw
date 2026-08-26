package com.xinl.easyclaw.mcp.service;

import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import java.util.List;
import java.util.Map;
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
     * 返回所有已建立连接的 MCP 客户端（key = serviceId，供 Agent Toolkit 注册）
     */
    Map<Long, McpClientWrapper> getConnectedWrappers();

    /**
     * 返回所有已激活的 HTTP_TOOL 桥接工具（供 Agent Toolkit 注册为 AgentTool）
     */
    List<AgentTool> getHttpTools();

    /**
     * 获取指定服务的启用工具列表（null/空 = 全部启用）
     */
    List<String> getEnabledTools(Long serviceId);

    /**
     * 更新指定服务的启用工具列表
     */
    McpServiceEntity updateEnabledTools(Long serviceId, List<String> enabledTools);

    McpServiceEntity connect(Long id);

    McpServiceEntity disconnect(Long id);

    List<McpServiceEntity> findAllTemplates();

    /**
     * 从模板复制一个 MCP 服务实例
     * @param templateId 模板 ID（必须 isTemplate=true）
     * @param scope 目标 scope：GLOBAL 或 WORKSPACE
     * @param workspaceId scope=WORKSPACE 时必填
     */
    McpServiceEntity copyFromTemplate(Long templateId, String scope, String workspaceId);
}
