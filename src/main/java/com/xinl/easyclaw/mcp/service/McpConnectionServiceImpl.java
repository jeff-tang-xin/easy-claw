package com.xinl.easyclaw.mcp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.repository.McpServiceRepository;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 服务连接管理实现
 * <p>
 * 提供 MCP 服务的注册、连接、断开等操作。
 * 连接成功后建立真正的 {@link McpClientWrapper} 并缓存，
 * Workspace 的 Agent Toolkit 可通过 {@link #getConnectedWrappers()} 注册这些 MCP 工具。
 */
@Service
public class McpConnectionServiceImpl implements McpConnectionService {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionServiceImpl.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final McpServiceRepository repository;
    /** 已建立连接的 MCP 客户端缓存 */
    private final Map<Long, McpClientWrapper> clientCache = new ConcurrentHashMap<>();

    public McpConnectionServiceImpl(McpServiceRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public McpServiceEntity create(McpServiceEntity service) {
        if (repository.existsByName(service.getName())) {
            throw new IllegalArgumentException("MCP 服务名称已存在: " + service.getName());
        }
        if (service.getTransport() == null || service.getTransport().isBlank()) {
            service.setTransport("SSE");
        }
        McpServiceEntity saved = repository.save(service);
        log.info("创建 MCP 服务: name={}, transport={}", service.getName(), service.getTransport());
        return saved;
    }

    @Override
    @Transactional
    public McpServiceEntity update(Long id, McpServiceEntity service) {
        return repository.findById(id)
                .map(existing -> {
                    existing.setDescription(service.getDescription());
                    existing.setSseUrl(service.getSseUrl());
                    existing.setTransport(service.getTransport());
                    existing.setCommand(service.getCommand());
                    existing.setArgs(service.getArgs());
                    existing.setEnv(service.getEnv());
                    existing.setAuthType(service.getAuthType());
                    existing.setAuthConfig(service.getAuthConfig());
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
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("MCP 服务不存在: id=" + id);
        }
        closeClient(id);
        repository.deleteById(id);
        log.info("删除 MCP 服务: id={}", id);
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
        return repository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<McpServiceEntity> findConnectedServices() {
        return repository.findByIsConnectedTrue();
    }

    /**
     * 返回所有已建立连接的 MCP 客户端（供 Agent Toolkit 注册）
     */
    @Override
    public List<McpClientWrapper> getConnectedWrappers() {
        return new ArrayList<>(clientCache.values());
    }

    @Override
    @Transactional
    public McpServiceEntity connect(Long id) {
        return repository.findById(id)
                .map(existing -> {
                    try {
                        McpClientWrapper wrapper = buildClient(existing);
                        clientCache.put(id, wrapper);
                        existing.setIsConnected(true);
                        existing.setLastConnected(Instant.now());
                        McpServiceEntity updated = repository.save(existing);
                        log.info("连接 MCP 服务成功: id={}, name={}, transport={}",
                                id, existing.getName(), existing.getTransport());
                        return updated;
                    } catch (Exception e) {
                        closeClient(id);
                        existing.setIsConnected(false);
                        repository.save(existing);
                        throw new IllegalArgumentException("连接 MCP 服务失败: " + e.getMessage(), e);
                    }
                })
                .orElseThrow(() -> new IllegalArgumentException("MCP 服务不存在: id=" + id));
    }

    @Override
    @Transactional
    public McpServiceEntity disconnect(Long id) {
        return repository.findById(id)
                .map(existing -> {
                    closeClient(id);
                    existing.setIsConnected(false);
                    McpServiceEntity updated = repository.save(existing);
                    log.info("断开 MCP 服务: id={}, name={}", id, existing.getName());
                    return updated;
                })
                .orElseThrow(() -> new IllegalArgumentException("MCP 服务不存在: id=" + id));
    }

    /**
     * 按实体配置构建 MCP 客户端
     */
    private McpClientWrapper buildClient(McpServiceEntity entity) throws Exception {
        String transport = entity.getTransport() != null
                ? entity.getTransport().toUpperCase() : "SSE";
        McpClientBuilder builder = McpClientBuilder.create(entity.getName());

        // 认证头
        applyAuth(builder, entity);

        switch (transport) {
            case "STDIO" -> {
                String command = entity.getCommand();
                if (command == null || command.isBlank()) {
                    throw new IllegalArgumentException("STDIO 传输需要配置启动命令 command");
                }
                List<String> args = parseStringList(entity.getArgs());
                Map<String, String> env = parseStringMap(entity.getEnv());
                builder.stdioTransport(command, args, env);
            }
            case "HTTP" -> builder.streamableHttpTransport(entity.getSseUrl());
            case "SSE" -> builder.sseTransport(entity.getSseUrl());
            default -> throw new IllegalArgumentException("不支持的传输协议: " + transport);
        }
        return builder.buildAsync().block();
    }

    private void applyAuth(McpClientBuilder builder, McpServiceEntity entity) {
        String authType = entity.getAuthType() != null ? entity.getAuthType().toUpperCase() : "NONE";
        String authConfig = entity.getAuthConfig();
        switch (authType) {
            case "BEARER" -> {
                if (authConfig != null && !authConfig.isBlank()) {
                    builder.header("Authorization", "Bearer " + authConfig.trim());
                }
            }
            case "API_KEY" -> {
                Map<String, String> cfg = parseStringMap(authConfig);
                String headerName = cfg.getOrDefault("header", "X-API-Key");
                String value = cfg.getOrDefault("value", "");
                if (!value.isBlank()) {
                    builder.header(headerName, value);
                }
            }
            default -> {
                // NONE
            }
        }
    }

    private void closeClient(Long id) {
        McpClientWrapper wrapper = clientCache.remove(id);
        if (wrapper != null) {
            // McpClientWrapper 生命周期由 MCP 框架管理，这里仅从缓存移除
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
            String[] parts = json.trim().split("\\s+");
            List<String> list = new ArrayList<>();
            for (String p : parts) {
                if (!p.isBlank()) {
                    list.add(p);
                }
            }
            return list;
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
}
