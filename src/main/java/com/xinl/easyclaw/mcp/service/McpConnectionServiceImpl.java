package com.xinl.easyclaw.mcp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.repository.McpServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class McpConnectionServiceImpl implements McpConnectionService {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionServiceImpl.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final McpServiceRepository repository;
    /** 已建立连接的外部 MCP 客户端缓存 */
    private final Map<Long, Object> clientCache = new ConcurrentHashMap<>();
    /** 已激活的 HTTP_TOOL 桥接工具缓存 */
    private final Map<Long, Object> httpToolCache = new ConcurrentHashMap<>();

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
                    if (service.getWorkspaceId() != null) {
                        existing.setWorkspaceId(service.getWorkspaceId());
                    }
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
    public List<Object> getConnectedWrappers() {
        return new ArrayList<>(clientCache.values());
    }

    @Override
    @Transactional
    public McpServiceEntity connect(Long id) {
        return repository.findById(id)
                .map(existing -> {
                    String transport = existing.resolveTransport();
                    try {
                        /* TODO: migrate to Embabel - McpClientWrapper connect disabled */
                        if ("HTTP_TOOL".equals(transport)) {
                            existing.setIsConnected(true);
                            existing.setLastConnected(Instant.now());
                            existing.setServerName(existing.getName());
                            existing.setServerVersion("internal-http-bridge");
                            repository.save(existing);
                            return existing;
                        }
                        existing.setIsConnected(true);
                        existing.setLastConnected(Instant.now());
                        repository.save(existing);
                        return existing;
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
                    httpToolCache.remove(id);
                    existing.setIsConnected(false);
                    McpServiceEntity updated = repository.save(existing);
                    log.info("断开 MCP 服务: id={}, name={}", id, existing.getName());
                    return updated;
                })
                .orElseThrow(() -> new IllegalArgumentException("MCP 服务不存在: id=" + id));
    }

    @Override
    public List<Object> getHttpTools() {
        return new ArrayList<>(httpToolCache.values());
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

    private void closeClient(Long id) {
        clientCache.remove(id);
        log.info("MCP 客户端已从缓存移除: id={}", id);
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
