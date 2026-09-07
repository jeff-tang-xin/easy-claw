package com.xinl.easyclaw.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.service.McpConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把场景绑定的「MCP 服务名」展开为「MCP 工具名」集合。
 * <p>
 * <b>为什么要展开</b>：用户按服务名配置（如 {@code filesystem}）才符合直觉，
 * 但 harness 的工具白名单只认工具名；且 MCP 工具注册时<b>不加服务名前缀</b>
 * （见 {@code AgentFactory.registerMcpWithFilters}），无法靠前缀推断，
 * 必须查服务的实际工具清单。
 * <p>
 * 数据来源为 {@code McpServiceEntity.availableTools}（连接成功时写入的
 * {@code McpSchema.Tool} JSON 数组）。若服务从未连接过，该字段为空 ——
 * 此时无法展开，按「降级不阻断」处理：记 warn 并跳过，不抛异常。
 */
@Component
public class McpToolExpander {

    private static final Logger log = LoggerFactory.getLogger(McpToolExpander.class);

    private final McpConnectionService mcpService;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpToolExpander(McpConnectionService mcpService) {
        this.mcpService = mcpService;
    }

    /**
     * 展开服务名列表为工具名集合。
     *
     * @param serviceNames 场景绑定的 MCP 服务名；null/空返回空集
     * @return 工具名集合（去重、保序）；无法解析的服务被跳过
     */
    public Set<String> expand(List<String> serviceNames) {
        Set<String> toolNames = new LinkedHashSet<>();
        if (serviceNames == null || serviceNames.isEmpty()) {
            return toolNames;
        }

        List<McpServiceEntity> all;
        try {
            all = mcpService.findAll();
        } catch (Exception e) {
            log.warn("[McpToolExpander] 查询 MCP 服务列表失败，跳过展开: {}", e.getMessage());
            return toolNames;
        }

        for (String rawName : serviceNames) {
            if (rawName == null || rawName.isBlank()) {
                continue;
            }
            String wanted = rawName.trim();
            McpServiceEntity matched = all.stream()
                    .filter(e -> e.getName() != null
                            && e.getName().trim().equalsIgnoreCase(wanted))
                    .findFirst()
                    .orElse(null);
            if (matched == null) {
                log.warn("[McpToolExpander] 场景绑定的 MCP 服务不存在: {}（已跳过）", wanted);
                continue;
            }
            Set<String> expanded = toolNamesOf(matched);
            if (expanded.isEmpty()) {
                log.warn("[McpToolExpander] MCP 服务 {} 无可用工具清单"
                        + "（可能尚未成功连接过），无法展开", wanted);
                continue;
            }
            toolNames.addAll(expanded);
        }
        return toolNames;
    }

    /**
     * 取单个服务的有效工具名。
     * <p>若该服务配置了 {@code enabledTools} 子集，则与之求交 ——
     * 场景绑定不应绕过服务自身已有的工具开关（取更严的一方）。
     */
    private Set<String> toolNamesOf(McpServiceEntity entity) {
        Set<String> available = parseAvailableToolNames(entity.getAvailableTools());
        if (available.isEmpty()) {
            return available;
        }
        List<String> enabled;
        try {
            enabled = mcpService.getEnabledTools(entity.getId());
        } catch (Exception e) {
            log.debug("读取 enabledTools 失败，视为未限制: {}", e.getMessage());
            return available;
        }
        if (enabled == null || enabled.isEmpty()) {
            return available; // 未配置 = 该服务全部工具可用
        }
        Set<String> intersection = new LinkedHashSet<>();
        for (String name : available) {
            if (enabled.contains(name)) {
                intersection.add(name);
            }
        }
        return intersection;
    }

    /** 解析 availableTools JSON 数组，提取每个元素的 name 字段 */
    private Set<String> parseAvailableToolNames(String availableToolsJson) {
        Set<String> names = new LinkedHashSet<>();
        if (availableToolsJson == null || availableToolsJson.isBlank()) {
            return names;
        }
        try {
            JsonNode root = mapper.readTree(availableToolsJson);
            if (!root.isArray()) {
                return names;
            }
            for (JsonNode node : root) {
                JsonNode nameNode = node.get("name");
                if (nameNode != null && nameNode.isTextual()) {
                    String name = nameNode.asText().trim();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[McpToolExpander] 解析 availableTools 失败: {}", e.getMessage());
        }
        return names;
    }

    /** 便于日志与前端展示的规范化服务名 */
    public static String normalize(String serviceName) {
        return serviceName == null ? "" : serviceName.trim().toLowerCase(Locale.ROOT);
    }
}
