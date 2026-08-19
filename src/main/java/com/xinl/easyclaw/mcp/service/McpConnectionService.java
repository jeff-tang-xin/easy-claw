package com.xinl.easyclaw.mcp.service;

import com.xinl.easyclaw.mcp.entity.McpServiceEntity;

import java.util.List;
import java.util.Optional;

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
     * TODO: migrate to Embabel - was List<McpClientWrapper>
     */
    List<Object> getConnectedWrappers();

    /**
     * 返回所有已激活的 HTTP_TOOL 桥接工具（供 Agent Toolkit 注册）
     * TODO: migrate to Embabel - was List<AgentTool>
     */
    List<Object> getHttpTools();

    McpServiceEntity connect(Long id);

    McpServiceEntity disconnect(Long id);

    List<McpServiceEntity> findAllTemplates();

    McpServiceEntity copyFromTemplate(Long templateId, String scope, String workspaceId);

    /**
     * 内部使用：McpTool CRUD 后通知刷新 ActionRegistry。
     */
    void notifyChanged();
}
