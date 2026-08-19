package com.xinl.easyclaw.mcp.service;

import com.embabel.agent.api.tool.Tool;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.entity.McpToolEntity;
import com.xinl.easyclaw.mcp.repository.McpToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP_TOOL（REST 桥接）连接器。
 * <p>
 * 将 McpService/McpTool 转换为 Embabel {@link Tool}，并在运行时动态缓存/清理。
 */
@Component
public class HttpMcpConnector implements McpConnector {

    private static final Logger log = LoggerFactory.getLogger(HttpMcpConnector.class);

    private final McpToolRepository toolRepository;
    private final McpToolFactory mcpToolFactory;
    private final Map<Long, List<Tool>> toolCache = new ConcurrentHashMap<>();

    public HttpMcpConnector(McpToolRepository toolRepository,
                            McpToolFactory mcpToolFactory) {
        this.toolRepository = toolRepository;
        this.mcpToolFactory = mcpToolFactory;
    }

    @Override
    public boolean supports(McpServiceEntity service) {
        return service != null && "HTTP_TOOL".equalsIgnoreCase(service.resolveTransport());
    }

    @Override
    public void connect(McpServiceEntity service) {
        List<Tool> tools = new ArrayList<>();
        try {
            List<McpToolEntity> toolEntities = toolRepository
                    .findByServiceIdAndEnabledTrueOrderBySortOrderAsc(service.getId());
            if (toolEntities.isEmpty()) {
                tools.add(mcpToolFactory.createHttpTool(service));
            } else {
                for (McpToolEntity tool : toolEntities) {
                    tools.add(mcpToolFactory.createHttpTool(tool));
                }
            }
        } catch (Exception e) {
            log.warn("构建 HTTP_TOOL 工具失败: service={}, err={}", service.getName(), e.getMessage());
        }
        if (!tools.isEmpty()) {
            toolCache.put(service.getId(), List.copyOf(tools));
        }
    }

    @Override
    public void disconnect(Long serviceId) {
        toolCache.remove(serviceId);
    }

    @Override
    public List<Tool> getTools(Long serviceId) {
        return toolCache.getOrDefault(serviceId, List.of());
    }
}
