package com.xinl.easyclaw.mcp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.repository.McpServiceRepository;
import com.xinl.easyclaw.tools.http.HttpAgentTool;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.mcp.McpAsyncClientWrapper;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 服务连接管理实现
 * <p>
 * 实体字段对齐 Claude Desktop 标准 mcpServers JSON 格式，
 * 连接成功后从服务端抓取 serverInfo / instructions / capabilities 存入实体。
 */
@Service
public class McpConnectionServiceImpl implements McpConnectionService {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionServiceImpl.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(60);

    private final McpServiceRepository repository;
    /** 已建立连接的外部 MCP 客户端缓存 */
    private final Map<Long, McpClientWrapper> clientCache = new ConcurrentHashMap<>();
    /** 已激活的 HTTP_TOOL 桥接工具缓存（Easy-Claw 内部构建，不走 MCP 协议） */
    private final Map<Long, AgentTool> httpToolCache = new ConcurrentHashMap<>();

    public McpConnectionServiceImpl(McpServiceRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public McpServiceEntity create(McpServiceEntity service) {
        String scope = service.getScope() != null ? service.getScope().toUpperCase() : "GLOBAL";
        service.setScope(scope);
        if ("WORKSPACE".equals(scope) && (service.getWorkspaceId() == null || service.getWorkspaceId().isBlank())) {
            throw new IllegalArgumentException("WORKSPACE scope 必须提供 workspaceId");
        }
        if ("SYSTEM".equals(scope)) {
            throw new IllegalStateException("SYSTEM 级别的 MCP 服务只能由系统内置，禁止手动创建");
        }
        if (repository.findByNameAndScope(service.getName(), scope).isPresent()) {
            throw new IllegalArgumentException("同 scope 下 MCP 服务名称已存在: " + service.getName() + " (" + scope + ")");
        }
        // 自动推断 transport
        if (service.getTransport() == null || service.getTransport().isBlank()) {
            service.setTransport(inferTransport(service));
        }
        McpServiceEntity saved = repository.save(service);
        log.info("创建 MCP 服务: name={}, scope={}, workspaceId={}, transport={}",
                service.getName(), scope, service.getWorkspaceId(), service.getTransport());
        return saved;
    }

    @Override
    @Transactional
    public McpServiceEntity update(Long id, McpServiceEntity service) {
        return repository.findById(id)
                .map(existing -> {
                    if ("SYSTEM".equals(existing.getScope())) {
                        throw new IllegalStateException("SYSTEM 级别的 MCP 服务不可修改: " + existing.getName());
                    }
                    existing.setDescription(service.getDescription());
                    existing.setTransport(service.getTransport());
                    existing.setUrl(service.getUrl());
                    existing.setCommand(service.getCommand());
                    existing.setArgs(service.getArgs());
                    existing.setEnv(service.getEnv());
                    existing.setCwd(service.getCwd());
                    existing.setHeaders(service.getHeaders());
                    existing.setTimeoutSeconds(service.getTimeoutSeconds());
                    existing.setInitTimeoutSeconds(service.getInitTimeoutSeconds());
                    existing.setImplementationConfig(service.getImplementationConfig());
                    // workspaceId 允许修改（WORKSPACE scope 下迁移到另一个 workspace）
                    if (service.getWorkspaceId() != null) {
                        existing.setWorkspaceId(service.getWorkspaceId());
                    }
                    // 配置变更后强制断开，需重新连接
                    if (Boolean.TRUE.equals(existing.getIsConnected())) {
                        closeClient(existing.getId());
                        existing.setIsConnected(false);
                    }
                    McpServiceEntity updated = repository.save(existing);
                    log.info("更新 MCP 服务: id={}, name={}", id, existing.getName());
                    return updated;
                })
                .orElseThrow(() -> new IllegalArgumentException("MCP 服务不存在: id=" + id));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        repository.findById(id).ifPresent(entity -> {
            if ("SYSTEM".equals(entity.getScope())) {
                throw new IllegalStateException("SYSTEM 级别的 MCP 服务不可删除: " + entity.getName());
            }
            closeClient(id);
            repository.delete(entity);
            log.info("删除 MCP 服务: id={}, name={}", id, entity.getName());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<McpServiceEntity> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<McpServiceEntity> findByName(String name) {
        return repository.findByName(name);
    }

    @Override
    @Transactional(readOnly = true)
    public List<McpServiceEntity> findAll() {
        return repository.findAll().stream()
                .filter(e -> !Boolean.TRUE.equals(e.getIsTemplate()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<McpServiceEntity> findConnectedServices() {
        return repository.findByIsConnectedTrue();
    }

    @Override
    public List<McpClientWrapper> getConnectedWrappers() {
        return new ArrayList<>(clientCache.values());
    }

    @Override
    @Transactional
    public McpServiceEntity connect(Long id) {
        return repository.findById(id)
                .map(existing -> {
                    String transport = existing.resolveTransport();
                    try {
                        if ("HTTP_TOOL".equals(transport)) {
                            return connectHttpTool(existing);
                        }
                        return connectExternalMcp(existing);
                    } catch (Exception e) {
                        closeClient(id);
                        existing.setIsConnected(false);
                        repository.save(existing);
                        throw new IllegalArgumentException("连接 MCP 服务失败: " + e.getMessage(), e);
                    }
                })
                .orElseThrow(() -> new IllegalArgumentException("MCP 服务不存在: id=" + id));
    }

    /** 连接外部 MCP Server（STDIO / STREAMABLE_HTTP / SSE） */
    private McpServiceEntity connectExternalMcp(McpServiceEntity entity) throws Exception {
        McpClientWrapper wrapper = buildClient(entity);
        wrapper.initialize().block();
        captureServerInfo(entity, wrapper);
        clientCache.put(entity.getId(), wrapper);
        entity.setIsConnected(true);
        entity.setLastConnected(Instant.now());

        try {
            List<McpSchema.Tool> tools = wrapper.listTools().block();
            if (tools != null) {
                entity.setAvailableTools(mapper.writeValueAsString(tools));
            }
        } catch (Exception te) {
            log.warn("获取工具列表失败: {}", te.getMessage());
        }

        McpServiceEntity updated = repository.save(entity);
        log.info("连接外部 MCP 服务成功: id={}, name={}, transport={}, serverName={}, version={}",
                entity.getId(), entity.getName(), entity.getTransport(),
                entity.getServerName(), entity.getServerVersion());
        return updated;
    }

    /** 激活内置 HTTP_TOOL 桥接（不发起网络连接，只是构建 AgentTool 注册） */
    private McpServiceEntity connectHttpTool(McpServiceEntity entity) throws Exception {
        AgentTool tool = new HttpAgentTool(entity);
        httpToolCache.put(entity.getId(), tool);
        entity.setIsConnected(true);
        entity.setLastConnected(Instant.now());
        entity.setServerName(entity.getName());
        entity.setServerVersion("internal-http-bridge");
        entity.setServerInstructions(entity.getDescription());
        // 把生成的参数 schema 存到 availableTools 里方便展示
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", tool.getName());
        schema.put("description", tool.getDescription());
        schema.put("parameters", tool.getParameters());
        entity.setAvailableTools(mapper.writeValueAsString(List.of(schema)));

        McpServiceEntity updated = repository.save(entity);
        log.info("激活 HTTP_TOOL 桥接: id={}, name={}, params={}",
                entity.getId(), entity.getName(), tool.getParameters().get("properties"));
        return updated;
    }

    @Override
    @Transactional
    public McpServiceEntity disconnect(Long id) {
        return repository.findById(id)
                .map(existing -> {
                    closeClient(id);
                    httpToolCache.remove(id);
                    existing.setIsConnected(false);
                    McpServiceEntity updated = repository.save(existing);
                    log.info("断开 MCP 服务: id={}, name={}", id, existing.getName());
                    return updated;
                })
                .orElseThrow(() -> new IllegalArgumentException("MCP 服务不存在: id=" + id));
    }

    @Override
    public List<AgentTool> getHttpTools() {
        return new ArrayList<>(httpToolCache.values());
    }

    // ==================== 构建客户端 ====================

    private McpClientWrapper buildClient(McpServiceEntity entity) throws Exception {
        String transport = entity.resolveTransport();
        McpClientBuilder builder = McpClientBuilder.create(entity.getName());

        // 超时配置
        Duration timeout = entity.getTimeoutSeconds() != null
                ? Duration.ofSeconds(entity.getTimeoutSeconds()) : DEFAULT_TIMEOUT;
        Duration initTimeout = entity.getInitTimeoutSeconds() != null
                ? Duration.ofSeconds(entity.getInitTimeoutSeconds()) : DEFAULT_INIT_TIMEOUT;
        builder.timeout(timeout).initializationTimeout(initTimeout);

        // HTTP headers（替代原来的 authType/authConfig）
        Map<String, String> headers = parseStringMap(entity.getHeaders());
        if (!headers.isEmpty()) {
            builder.headers(headers);
        }

        switch (transport) {
            case "STDIO" -> buildStdio(entity, builder);
            case "STREAMABLE_HTTP" -> {
                if (entity.getUrl() == null || entity.getUrl().isBlank()) {
                    throw new IllegalArgumentException("STREAMABLE_HTTP 传输需要配置 url");
                }
                builder.streamableHttpTransport(entity.getUrl());
            }
            case "SSE" -> {
                if (entity.getUrl() == null || entity.getUrl().isBlank()) {
                    throw new IllegalArgumentException("SSE 传输需要配置 url");
                }
                builder.sseTransport(entity.getUrl());
            }
            default -> throw new IllegalArgumentException("不支持的传输协议: " + transport);
        }
        return builder.buildAsync().block();
    }

    private void buildStdio(McpServiceEntity entity, McpClientBuilder builder) {
        String command = entity.getCommand();
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("STDIO 传输需要配置启动命令 command");
        }
        List<String> args = parseStringList(entity.getArgs());
        Map<String, String> env = parseStringMap(entity.getEnv());
        builder.stdioTransport(command, args, env);
    }

    private String inferTransport(McpServiceEntity service) {
        if (service.getCommand() != null && !service.getCommand().isBlank()) {
            return "STDIO";
        }
        if (service.getUrl() != null && !service.getUrl().isBlank()) {
            return service.getUrl().toLowerCase().endsWith("/sse") ? "SSE" : "STREAMABLE_HTTP";
        }
        return "STREAMABLE_HTTP";
    }

    // ==================== 抓取服务端信息 ====================

    /**
     * 从已初始化的 wrapper 中提取 McpAsyncClient，抓取服务端返回的 serverInfo / instructions / capabilities
     */
    private void captureServerInfo(McpServiceEntity entity, McpClientWrapper wrapper) {
        McpAsyncClient asyncClient = extractAsyncClient(wrapper);
        if (asyncClient == null || !asyncClient.isInitialized()) {
            log.warn("无法从 McpClientWrapper 提取 McpAsyncClient，跳过服务端信息抓取");
            return;
        }
        try {
            McpSchema.Implementation info = asyncClient.getServerInfo();
            if (info != null) {
                entity.setServerName(info.name());
                entity.setServerTitle(info.title());
                entity.setServerVersion(info.version());
            }
            String instructions = asyncClient.getServerInstructions();
            if (instructions != null && !instructions.isBlank()) {
                entity.setServerInstructions(instructions);
            }
            McpSchema.ServerCapabilities caps = asyncClient.getServerCapabilities();
            if (caps != null) {
                entity.setCapabilities(mapper.writeValueAsString(caps));
            }
            log.info("MCP 服务端信息: name={}, title={}, version={}",
                    entity.getServerName(), entity.getServerTitle(), entity.getServerVersion());
        } catch (Exception e) {
            log.warn("抓取服务端信息失败: {}", e.getMessage());
        }
    }

    /**
     * 从 McpAsyncClientWrapper 反射拿到底层的 McpAsyncClient
     */
    private McpAsyncClient extractAsyncClient(McpClientWrapper wrapper) {
        if (!(wrapper instanceof McpAsyncClientWrapper asyncWrapper)) {
            return null;
        }
        try {
            Field clientField = McpAsyncClientWrapper.class.getDeclaredField("client");
            clientField.setAccessible(true);
            return (McpAsyncClient) clientField.get(asyncWrapper);
        } catch (Exception e) {
            log.warn("反射提取 McpAsyncClient 失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 工具方法 ====================

    private void closeClient(Long id) {
        McpClientWrapper wrapper = clientCache.remove(id);
        if (wrapper != null) {
            try {
                wrapper.close();
            } catch (Exception e) {
                log.warn("关闭 MCP 客户端异常: {}", e.getMessage());
            }
            log.info("MCP 客户端已从缓存移除: id={}", id);
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("解析 args JSON 失败，按空格切分: {}", e.getMessage());
            return List.of(json.trim().split("\\s+"));
        }
    }

    private Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            log.warn("解析 JSON 配置失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<McpServiceEntity> findAllTemplates() {
        return repository.findByIsTemplateTrue();
    }

    @Override
    @Transactional
    public McpServiceEntity copyFromTemplate(Long templateId, String scope, String workspaceId) {
        McpServiceEntity template = repository.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("MCP 模板不存在: id=" + templateId));
        if (!Boolean.TRUE.equals(template.getIsTemplate())) {
            throw new IllegalArgumentException("该 MCP 服务不是模板，无法复制: " + template.getName());
        }

        String targetScope = (scope == null || scope.isBlank()) ? "GLOBAL" : scope.toUpperCase();
        if (!"GLOBAL".equals(targetScope) && !"WORKSPACE".equals(targetScope)) {
            throw new IllegalArgumentException("scope 只能是 GLOBAL 或 WORKSPACE: " + targetScope);
        }
        if ("WORKSPACE".equals(targetScope) && (workspaceId == null || workspaceId.isBlank())) {
            throw new IllegalArgumentException("WORKSPACE scope 必须提供 workspaceId");
        }

        McpServiceEntity copy = McpServiceEntity.builder()
                .name(template.getName() + "-" + UUID.randomUUID().toString().substring(0, 6))
                .description(template.getDescription().replace("【内置模板】", ""))
                .transport(template.getTransport())
                .implementationConfig(template.getImplementationConfig())
                .headers(template.getHeaders())
                .url(template.getUrl())
                .command(template.getCommand())
                .args(template.getArgs())
                .env(template.getEnv())
                .cwd(template.getCwd())
                .timeoutSeconds(template.getTimeoutSeconds())
                .initTimeoutSeconds(template.getInitTimeoutSeconds())
                .scope(targetScope)
                .workspaceId("WORKSPACE".equals(targetScope) ? workspaceId : null)
                .isTemplate(false)
                .isConnected(false)
                .build();
        McpServiceEntity saved = repository.save(copy);
        log.info("从模板复制 MCP 服务: template={}, new={}, scope={}, workspaceId={}",
                template.getName(), copy.getName(), targetScope, copy.getWorkspaceId());
        return saved;
    }
}
