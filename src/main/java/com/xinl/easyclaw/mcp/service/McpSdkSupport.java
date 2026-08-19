package com.xinl.easyclaw.mcp.service;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.Tool.InputSchema;
import com.embabel.agent.api.tool.Tool.Metadata;
import com.embabel.agent.api.tool.Tool.Parameter;
import com.embabel.agent.api.tool.Tool.ParameterType;
import com.embabel.agent.api.tool.Tool.Result;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Java MCP SDK 的反射适配层。
 * <p>
 * 使用反射是为了让项目在未完全确定 Embabel MCP Client API 的情况下，
 * 仍可编译；运行时通过 io.modelcontextprotocol.sdk:mcp 提供的客户端能力连接真实 MCP Server。
 */
@Component
public class McpSdkSupport {

    private static final Logger log = LoggerFactory.getLogger(McpSdkSupport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 每个连接缓存 client + 工具列表 */
    private final Map<Long, McpConnection> connections = new java.util.concurrent.ConcurrentHashMap<>();

    public record McpToolInfo(String name, String description) {}

    public record McpConnection(Object client, List<McpToolInfo> tools) {}

    public boolean connect(McpServiceEntity service) {
        try {
            Object transport = createTransport(service);
            Object client = createSyncClient(transport);
            List<McpToolInfo> tools = listTools(client);
            connections.put(service.getId(), new McpConnection(client, tools));
            log.info("MCP SDK 连接成功: name={}, transport={}, tools={}",
                    service.getName(), service.resolveTransport(), tools.size());
            return true;
        } catch (Exception e) {
            log.warn("MCP SDK 连接失败: name={}, transport={}, err={}",
                    service.getName(), service.resolveTransport(), e.getMessage());
            return false;
        }
    }

    public void disconnect(Long serviceId) {
        McpConnection connection = connections.remove(serviceId);
        if (connection != null && connection.client() != null) {
            try {
                Method close = connection.client().getClass().getMethod("close");
                close.invoke(connection.client());
            } catch (Exception e) {
                log.debug("关闭 MCP client 失败: id={}, err={}", serviceId, e.getMessage());
            }
        }
    }

    public List<McpToolInfo> getTools(Long serviceId) {
        McpConnection connection = connections.get(serviceId);
        return connection == null ? List.of() : connection.tools();
    }

    public Tool toEmbabelTool(Long serviceId, McpToolInfo toolInfo) {
        return Tool.create(
                toolInfo.name(),
                toolInfo.description() == null || toolInfo.description().isBlank()
                        ? "MCP: " + toolInfo.name() : toolInfo.description(),
                InputSchema.of(new Parameter("input", ParameterType.STRING,
                        "JSON 请求参数，例如 {\"key\":\"value\"}", false, List.of(), List.of(), null)),
                Metadata.create(),
                json -> invokeTool(serviceId, toolInfo.name(), json)
        );
    }

    private Result invokeTool(Long serviceId, String toolName, String jsonInput) {
        McpConnection connection = connections.get(serviceId);
        if (connection == null) {
            return Result.error("MCP 连接不存在: serviceId=" + serviceId);
        }
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            if (jsonInput != null && !jsonInput.isBlank()) {
                try {
                    args = MAPPER.readValue(jsonInput, new TypeReference<>() {});
                } catch (Exception ignored) {
                    // 非 JSON 时作为单个 text 参数
                    args.put("input", jsonInput);
                }
            }
            Object request = createCallToolRequest(toolName, args);
            Method callTool = connection.client().getClass().getMethod("callTool", request.getClass());
            Object result = callTool.invoke(connection.client(), request);
            return Result.text(extractText(result));
        } catch (Exception e) {
            log.warn("MCP 工具调用失败: serviceId={}, tool={}, err={}", serviceId, toolName, e.getMessage());
            return Result.error("MCP 调用失败: " + e.getMessage());
        }
    }

    private Object createTransport(McpServiceEntity service) throws Exception {
        String transport = service.resolveTransport();
        return switch (transport) {
            case "STDIO" -> createStdioTransport(service);
            case "SSE" -> createSseTransport(service.getUrl());
            case "STREAMABLE_HTTP" -> createStreamableHttpTransport(service.getUrl());
            default -> throw new IllegalArgumentException("不支持的 MCP transport: " + transport);
        };
    }

    private Object createStdioTransport(McpServiceEntity service) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(service.getCommand());
        if (service.getArgs() != null && !service.getArgs().isBlank()) {
            try {
                List<String> args = MAPPER.readValue(service.getArgs(), new TypeReference<>() {});
                command.addAll(args);
            } catch (Exception e) {
                command.add(service.getArgs());
            }
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        if (service.getCwd() != null && !service.getCwd().isBlank()) {
            pb.directory(new java.io.File(service.getCwd()));
        }
        if (service.getEnv() != null && !service.getEnv().isBlank()) {
            try {
                Map<String, String> env = MAPPER.readValue(service.getEnv(), new TypeReference<>() {});
                pb.environment().putAll(env);
            } catch (Exception ignored) {
                // 忽略环境变量解析失败
            }
        }
        Class<?> transportClass = Class.forName("io.modelcontextprotocol.client.transport.StdioClientTransport");
        Constructor<?> ctor = transportClass.getConstructor(ProcessBuilder.class);
        return ctor.newInstance(pb);
    }

    private Object createSseTransport(String url) throws Exception {
        Class<?> transportClass = Class.forName("io.modelcontextprotocol.client.transport.HttpClientSseClientTransport");
        Constructor<?> ctor = transportClass.getConstructor(String.class);
        return ctor.newInstance(url);
    }

    private Object createStreamableHttpTransport(String url) throws Exception {
        String[] candidates = {
                "io.modelcontextprotocol.client.transport.HttpClientStreamableTransport",
                "io.modelcontextprotocol.client.transport.HttpClientStreamableClientTransport",
                "io.modelcontextprotocol.client.transport.WebFluxStreamableTransport"
        };
        Exception last = null;
        for (String className : candidates) {
            try {
                Class<?> transportClass = Class.forName(className);
                Constructor<?> ctor = transportClass.getConstructor(String.class);
                return ctor.newInstance(url);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException("找不到 Streamable HTTP MCP Transport: " + last, last);
    }

    private Object createSyncClient(Object transport) throws Exception {
        Class<?> clientClass = Class.forName("io.modelcontextprotocol.client.McpClient");
        Method sync = clientClass.getMethod("sync", transport.getClass());
        Object builder = sync.invoke(null, transport);
        Method build = builder.getClass().getMethod("build");
        return build.invoke(builder);
    }

    private List<McpToolInfo> listTools(Object client) throws Exception {
        Method listTools = client.getClass().getMethod("listTools");
        Object result = listTools.invoke(client);
        Method toolsMethod = result.getClass().getMethod("tools");
        List<?> tools = (List<?>) toolsMethod.invoke(result);
        List<McpToolInfo> infos = new ArrayList<>();
        if (tools != null) {
            for (Object tool : tools) {
                String name = String.valueOf(tool.getClass().getMethod("name").invoke(tool));
                Object descObj = null;
                try {
                    descObj = tool.getClass().getMethod("description").invoke(tool);
                } catch (Exception ignored) {
                    // description may be absent
                }
                infos.add(new McpToolInfo(name, descObj == null ? "" : String.valueOf(descObj)));
            }
        }
        return infos;
    }

    private Object createCallToolRequest(String toolName, Map<String, Object> arguments) throws Exception {
        Class<?> schemaClass = Class.forName("io.modelcontextprotocol.spec.McpSchema");
        Class<?>[] nested = schemaClass.getClasses();
        Class<?> requestClass = null;
        for (Class<?> c : nested) {
            if (c.getSimpleName().equals("CallToolRequest")) {
                requestClass = c;
                break;
            }
        }
        if (requestClass == null) {
            requestClass = Class.forName("io.modelcontextprotocol.spec.McpSchema$CallToolRequest");
        }
        Constructor<?> ctor = requestClass.getConstructor(String.class, Map.class);
        return ctor.newInstance(toolName, arguments);
    }

    private String extractText(Object result) throws Exception {
        if (result == null) {
            return "";
        }
        try {
            Method contentMethod = result.getClass().getMethod("content");
            Object content = contentMethod.invoke(result);
            if (content instanceof List<?> list) {
                StringBuilder sb = new StringBuilder();
                for (Object item : list) {
                    try {
                        Object text = item.getClass().getMethod("text").invoke(item);
                        if (text != null) {
                            sb.append(text);
                        }
                    } catch (Exception ignored) {
                        sb.append(item);
                    }
                }
                if (!sb.isEmpty()) {
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {
            // fall through to toString
        }
        return String.valueOf(result);
    }
}
