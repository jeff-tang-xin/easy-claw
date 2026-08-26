package com.xinl.easyclaw.tools.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.tool.entity.ToolDefinitionEntity;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 用户定义的 HTTP REST API 工具，作为 MCP tool 注册给 Agent 调用。
 * <p>
 * 配置 JSON 示例（存于 ToolDefinitionEntity.implementationConfig）：
 * <pre>{@code
 * {
 *   "method": "GET",
 *   "url": "https://api.weather.com/city/{city}",
 *   "headers": {"Authorization": "Bearer xxx"},
 *   "params": {"city": {"in": "path", "required": true, "type": "string", "description": "城市名"}},
 *   "body": {"mode": "json"},
 *   "timeout": 15
 * }
 * }</pre>
 * <p>
 * 参数的 {@code in} 字段：path（URL 模板变量）/ query（URL 查询参数）/ body（JSON body 字段）
 */
public class HttpAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(HttpAgentTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient DEFAULT_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String name;
    private final String description;
    private final ToolSchema schema;
    private final HttpToolConfig config;

    public HttpAgentTool(ToolDefinitionEntity entity) {
        this.name = entity.getName();
        this.description = entity.getDescription() != null
                ? entity.getDescription() : "HTTP 工具：" + entity.getName();
        this.config = HttpToolConfig.parse(entity.getImplementationConfig(), null);
        this.schema = buildSchema();
    }

    /**
     * 从 MCP HTTP_TOOL 桥接服务构建
     * <p>
     * McpServiceEntity.headers 会合并到 config.headers（implementationConfig 里的 headers 优先）
     */
    public HttpAgentTool(McpServiceEntity entity) {
        this.name = entity.getName();
        this.description = entity.getDescription() != null
                ? entity.getDescription() : "REST 桥接：" + entity.getName();
        this.config = HttpToolConfig.parse(entity.getImplementationConfig(), entity.getHeaders());
        this.schema = buildSchema();
    }

    private ToolSchema buildSchema() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, ParamDef> e : config.params.entrySet()) {
            ParamDef p = e.getValue();
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", p.type);
            prop.put("description", p.description != null ? p.description : e.getKey());
            if (p.enumValues != null) prop.put("enum", p.enumValues);
            props.put(e.getKey(), prop);
            if (p.required) required.add(e.getKey());
        }
        parameters.put("properties", props);
        if (!required.isEmpty()) parameters.put("required", required);
        return ToolSchema.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .build();
    }

    @Override public String getName() { return name; }
    @Override public String getDescription() { return description; }
    @Override public Map<String, Object> getParameters() { return schema.getParameters(); }
    @Override public boolean isReadOnly() { return switch (config.method.toUpperCase()) {
        case "GET", "HEAD", "OPTIONS" -> true;
        default -> false;
    }; }

    @Override
    public reactor.core.publisher.Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        return reactor.core.publisher.Mono.fromCallable(() -> execute(input))
                .map(body -> ToolResultBlock.text(body))
                .onErrorResume(ex -> {
                    log.error("HTTP 工具执行失败: {}", ex.getMessage());
                    return reactor.core.publisher.Mono.just(ToolResultBlock.error("HTTP 工具调用失败: " + ex.getMessage()));
                });
    }

    private String execute(Map<String, Object> args) throws Exception {
        // 1. 替换 URL 模板变量
        String url = config.url;
        Map<String, Object> queryParams = new LinkedHashMap<>();
        Map<String, Object> bodyFields = new LinkedHashMap<>();

        for (Map.Entry<String, ParamDef> e : config.params.entrySet()) {
            String key = e.getKey();
            ParamDef p = e.getValue();
            Object value = args.get(key);
            if (value == null) continue;
            switch (p.in) {
                case "path" -> url = url.replace("{" + key + "}", URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
                case "query" -> queryParams.put(key, value);
                case "body" -> bodyFields.put(key, value);
            }
        }

        // 2. 追加 query string
        if (!queryParams.isEmpty()) {
            StringJoiner sj = new StringJoiner("&");
            for (Map.Entry<String, Object> e : queryParams.entrySet()) {
                sj.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
            }
            url = url + (url.contains("?") ? "&" : "?") + sj;
        }

        // 3. 构建请求
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.timeout));

        // headers
        for (Map.Entry<String, String> h : config.headers.entrySet()) {
            reqBuilder.header(h.getKey(), h.getValue());
        }

        String method = config.method.toUpperCase();
        HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.noBody();

        if (Set.of("POST", "PUT", "PATCH").contains(method)) {
            if (!bodyFields.isEmpty() || "json".equalsIgnoreCase(config.bodyMode)) {
                reqBuilder.header("Content-Type", "application/json");
                String json = MAPPER.writeValueAsString(bodyFields);
                bodyPublisher = HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
            }
        }

        reqBuilder.method(method, bodyPublisher);
        HttpRequest request = reqBuilder.build();

        log.info("HTTP 工具调用: {} {} ({} params)", method, url, args.size());
        HttpResponse<String> response = DEFAULT_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        // 4. 处理响应
        int status = response.statusCode();
        String respBody = response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("");

        StringBuilder result = new StringBuilder();
        result.append("HTTP ").append(status).append(" ").append(getStatusText(status)).append("\n");

        if (respBody != null && !respBody.isBlank()) {
            if (contentType.contains("application/json")) {
                try {
                    Object json = MAPPER.readValue(respBody, Object.class);
                    String pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json);
                    result.append(pretty.length() > 8000 ? pretty.substring(0, 8000) + "\n... (truncated)" : pretty);
                } catch (Exception ignored) {
                    result.append(respBody.length() > 8000 ? respBody.substring(0, 8000) + "\n... (truncated)" : respBody);
                }
            } else {
                result.append(respBody.length() > 4000 ? respBody.substring(0, 4000) + "\n... (truncated)" : respBody);
            }
        }
        return result.toString();
    }

    private static String getStatusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 422 -> "Unprocessable Entity";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "";
        };
    }

    // ==================== 配置解析 ====================

    static class HttpToolConfig {
        String method = "GET";
        String url = "";
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, ParamDef> params = new LinkedHashMap<>();
        String bodyMode = "json";
        int timeout = 15;

        static HttpToolConfig parse(String json, String extraHeadersJson) {
            HttpToolConfig cfg = new HttpToolConfig();
            if (json != null && !json.isBlank()) {
                try {
                    Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() {});
                    if (map.get("method") != null) cfg.method = String.valueOf(map.get("method"));
                    if (map.get("url") != null) cfg.url = String.valueOf(map.get("url"));
                    else if (map.get("urlTemplate") != null) cfg.url = String.valueOf(map.get("urlTemplate"));
                    if (map.get("timeout") instanceof Number n) cfg.timeout = n.intValue();
                    if (map.get("bodyMode") != null) cfg.bodyMode = String.valueOf(map.get("bodyMode"));
                    if (map.get("headers") instanceof Map<?, ?> hm) {
                        for (Map.Entry<?, ?> e : hm.entrySet()) {
                            cfg.headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                        }
                    }
                    if (map.get("params") instanceof Map<?, ?> pm) {
                        for (Map.Entry<?, ?> e : pm.entrySet()) {
                            String key = String.valueOf(e.getKey());
                            @SuppressWarnings("unchecked")
                            Map<String, Object> def = (Map<String, Object>) e.getValue();
                            cfg.params.put(key, ParamDef.parse(def));
                        }
                    }
                } catch (Exception ex) {
                    log.warn("解析 HTTP 工具配置失败: {}", ex.getMessage());
                }
            }
            // 合并 McpServiceEntity.headers（implementationConfig 里的同名 header 优先）
            if (extraHeadersJson != null && !extraHeadersJson.isBlank()) {
                try {
                    Map<String, String> extra = MAPPER.readValue(extraHeadersJson, new TypeReference<>() {});
                    for (Map.Entry<String, String> e : extra.entrySet()) {
                        cfg.headers.putIfAbsent(e.getKey(), e.getValue());
                    }
                } catch (Exception ignored) {
                }
            }
            return cfg;
        }
    }

    static class ParamDef {
        String in = "query";      // path | query | body
        String type = "string";
        String description;
        boolean required = false;
        List<String> enumValues;

        static ParamDef parse(Map<String, Object> m) {
            ParamDef p = new ParamDef();
            if (m.get("in") != null) p.in = String.valueOf(m.get("in"));
            if (m.get("type") != null) p.type = String.valueOf(m.get("type"));
            if (m.get("description") != null) p.description = String.valueOf(m.get("description"));
            if (m.get("required") instanceof Boolean b) p.required = b;
            if (m.get("enum") instanceof List<?> l) p.enumValues = l.stream().map(String::valueOf).toList();
            return p;
        }
    }
}
