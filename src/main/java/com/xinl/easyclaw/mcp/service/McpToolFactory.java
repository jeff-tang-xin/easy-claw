package com.xinl.easyclaw.mcp.service;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.Tool.InputSchema;
import com.embabel.agent.api.tool.Tool.Metadata;
import com.embabel.agent.api.tool.Tool.Parameter;
import com.embabel.agent.api.tool.Tool.ParameterType;
import com.embabel.agent.api.tool.Tool.Result;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.embabel.domain.WorkspaceContextData.McpToolBridge;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.entity.McpToolEntity;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP HTTP_TOOL 工具工厂。
 * <p>
 * 把 McpService/McpTool 的 REST 配置转换为 Embabel {@link Tool}，
 * 供 OrchestratorAgent 在每次会话中按 Scenario 动态加载。
 */
@Component
public class McpToolFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient httpClient;

    public McpToolFactory() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public Tool createHttpTool(McpToolEntity tool) throws Exception {
        return createHttpTool(buildBridgeFromTool(tool));
    }

    public Tool createHttpTool(McpServiceEntity svc) throws Exception {
        return createHttpTool(buildBridgeFromMcp(svc));
    }

    public Tool createHttpTool(McpToolBridge bridge) {
        List<Parameter> params = List.of(
                new Parameter("input", ParameterType.STRING, "JSON 请求参数", false, List.of(), List.of(), null)
        );
        return Tool.create(bridge.toolName(),
                bridge.description() != null ? bridge.description() : "MCP: " + bridge.toolName(),
                InputSchema.of(params.toArray(new Parameter[0])),
                Metadata.create(),
                json -> executeHttpMcp(bridge, json));
    }

    private Result executeHttpMcp(McpToolBridge bridge, String jsonInput) {
        try {
            Map<String, Object> params = new HashMap<>();
            if (jsonInput != null && !jsonInput.isBlank()) {
                try {
                    params = MAPPER.readValue(jsonInput, new TypeReference<>() {});
                } catch (Exception ignored) {
                    // 入参不是 JSON 时按空参数处理
                }
            }
            String url = bridge.urlTemplate();
            for (Map.Entry<String, Object> e : params.entrySet()) {
                url = url.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
            }
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url)).timeout(Duration.ofSeconds(30));
            if (bridge.headers() != null) {
                for (Map.Entry<String, String> h : bridge.headers().entrySet()) {
                    reqBuilder.header(h.getKey(), h.getValue());
                }
            }
            String method = bridge.httpMethod() != null ? bridge.httpMethod().toUpperCase() : "GET";
            String bodyMode = bridge.bodyMode() != null ? bridge.bodyMode().toLowerCase() : "none";
            String body = "";

            // 识别已用于 path 替换的参数，剩余参数作为 query 或 body
            Set<String> pathParams = new HashSet<>();
            for (String key : params.keySet()) {
                if (bridge.urlTemplate() != null && bridge.urlTemplate().contains("{" + key + "}")) {
                    pathParams.add(key);
                }
            }

            boolean useQuery = method.equals("GET") || method.equals("DELETE") || method.equals("HEAD")
                    || bodyMode.equals("none") || bodyMode.equals("query");
            if (useQuery) {
                StringBuilder query = new StringBuilder();
                for (Map.Entry<String, Object> e : params.entrySet()) {
                    if (pathParams.contains(e.getKey())) continue;
                    if (e.getValue() == null) continue;
                    if (query.length() > 0) query.append("&");
                    query.append(java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                            .append("=")
                            .append(java.net.URLEncoder.encode(String.valueOf(e.getValue()), java.nio.charset.StandardCharsets.UTF_8));
                }
                if (query.length() > 0) {
                    url += (url.contains("?") ? "&" : "?") + query;
                }
            } else if (bridge.bodyTemplate() != null && !bridge.bodyTemplate().isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tmpl = MAPPER.readValue(bridge.bodyTemplate(), new TypeReference<>() {});
                    replaceInMap(tmpl, params);
                    body = MAPPER.writeValueAsString(tmpl);
                } catch (Exception ignored) {
                    // 模板解析失败时回退到原始参数
                }
            } else if (!params.isEmpty()) {
                body = MAPPER.writeValueAsString(params);
            }
            HttpRequest.BodyPublisher bp = body.isBlank()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            reqBuilder.method(method, bp);
            HttpResponse<String> resp = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return Result.text(resp.body() != null ? resp.body() : "");
            }
            return Result.text("HTTP " + resp.statusCode() + ": " + resp.body());
        } catch (Exception e) {
            return Result.error("MCP 调用失败: " + e.getMessage());
        }
    }

    private McpToolBridge buildBridgeFromTool(McpToolEntity tool) throws Exception {
        String config = tool.getToolConfig();
        Map<String, Object> cfg = config != null && !config.isBlank()
                ? MAPPER.readValue(config, new TypeReference<>() {})
                : new LinkedHashMap<>();
        String name = tool.getToolName();
        String desc = tool.getDescription() != null ? tool.getDescription()
                : (tool.getDisplayName() != null ? tool.getDisplayName() : name);
        String parentHeaders = tool.getService() != null ? tool.getService().getHeaders() : null;
        return buildBridgeFromParts(name, desc, cfg, parentHeaders);
    }

    private McpToolBridge buildBridgeFromMcp(McpServiceEntity mcp) throws Exception {
        String config = mcp.getImplementationConfig();
        Map<String, Object> cfg = config != null && !config.isBlank()
                ? MAPPER.readValue(config, new TypeReference<>() {})
                : new LinkedHashMap<>();
        return buildBridgeFromParts(mcp.getName(), mcp.getDescription(), cfg, mcp.getHeaders());
    }

    private McpToolBridge buildBridgeFromParts(String name, String description,
                                                Map<String, Object> cfg,
                                                String headersJson) throws Exception {
        String method = String.valueOf(cfg.getOrDefault("method", "GET"));
        String urlTemplate = cfg.containsKey("urlTemplate")
                ? String.valueOf(cfg.get("urlTemplate"))
                : (cfg.containsKey("url") ? String.valueOf(cfg.get("url")) : "");
        String bodyMode = String.valueOf(cfg.getOrDefault("bodyMode", "none"));
        String bodyTemplate = cfg.containsKey("bodyTemplate")
                ? MAPPER.writeValueAsString(cfg.get("bodyTemplate"))
                : null;
        Map<String, String> headers = new LinkedHashMap<>();
        if (headersJson != null && !headersJson.isBlank()) {
            try {
                headers = MAPPER.readValue(headersJson, new TypeReference<>() {});
            } catch (Exception ignored) {
                // 保留空 headers
            }
        }
        return new McpToolBridge(name, description, method, urlTemplate, bodyMode, bodyTemplate, headers);
    }

    @SuppressWarnings("unchecked")
    private void replaceInMap(Map<String, Object> map, Map<String, Object> params) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (e.getValue() instanceof String s) {
                String r = s;
                for (Map.Entry<String, Object> p : params.entrySet()) {
                    r = r.replace("{" + p.getKey() + "}", String.valueOf(p.getValue()));
                }
                if (!r.equals(s)) e.setValue(r);
            } else if (e.getValue() instanceof Map m) {
                replaceInMap(m, params);
            }
        }
    }
}
