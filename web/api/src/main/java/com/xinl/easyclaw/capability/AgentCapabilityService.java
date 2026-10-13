package com.xinl.easyclaw.capability;

import com.xinl.easyclaw.agent.AgentCapabilitySource;
import com.xinl.easyclaw.scenario.McpToolExpander;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 智能体能力绑定服务：存储 + 读取，并作为 {@link AgentCapabilitySource} 供 agent-core 消费。
 * <p>
 * <b>写入采用「按 kind 整组覆盖」</b>而不是逐条增删：绑定关系的语义是「这个智能体用这一组」，
 * 逐条 diff 会让「取消勾选」与「从未勾选」在数据上无法区分，而这两者在生效逻辑上
 * 必须一致（都表现为不在名单内）。整组覆盖天然没有这个问题，也让前端提交幂等。
 */
@Service
public class AgentCapabilityService implements AgentCapabilitySource {

    private static final Logger log = LoggerFactory.getLogger(AgentCapabilityService.class);

    /** 能力类型白名单：未知 kind 直接拒绝，避免脏数据静默不生效 */
    private static final Set<String> KINDS = Set.of(KIND_MCP, "skill", "tool");

    private final AgentCapabilityRepository repository;
    private final McpToolExpander mcpToolExpander;

    public AgentCapabilityService(AgentCapabilityRepository repository,
                                  McpToolExpander mcpToolExpander) {
        this.repository = repository;
        this.mcpToolExpander = mcpToolExpander;
    }

    /** 一条绑定：能力标识 + 是否启用（null 视为启用） */
    public record Binding(String name, Boolean enabled) {
    }

    /** 某智能体的全部绑定（按 kind、名称排序，供前端回显） */
    public List<AgentCapabilityEntity> list(String agentId) {
        return repository.findByAgentIdOrderByKindAscNameAsc(normalizeAgentId(agentId));
    }

    /**
     * 整组覆盖某智能体某 kind 的绑定。
     *
     * @param items 空列表 = 清空该 kind 的全部绑定
     */
    @Transactional
    public List<AgentCapabilityEntity> replace(String agentId, String kind, List<Binding> items) {
        String aid = requireAgentId(agentId);
        String k = normalizeKind(kind);
        // 同名去重：后出现的覆盖先前条目，保证「一次提交里勾了又取消」按取消生效
        Map<String, Binding> deduped = new java.util.LinkedHashMap<>();
        if (items != null) {
            for (Binding item : items) {
                if (item == null || item.name() == null || item.name().isBlank()) {
                    continue;
                }
                deduped.put(item.name().trim(), item);
            }
        }
        repository.deleteByAgentIdAndKind(aid, k);
        List<AgentCapabilityEntity> saved = new ArrayList<>();
        Instant now = Instant.now();
        for (Map.Entry<String, Binding> entry : deduped.entrySet()) {
            saved.add(repository.save(AgentCapabilityEntity.builder()
                    .agentId(aid)
                    .kind(k)
                    .name(entry.getKey())
                    .enabled(entry.getValue().enabled() == null || entry.getValue().enabled())
                    .updatedAt(now)
                    .build()));
        }
        log.info("智能体能力绑定已更新: agentId={}, kind={}, 条目 {}（启用 {}）",
                aid, k, saved.size(), saved.stream().filter(AgentCapabilityEntity::isEnabled).count());
        return saved;
    }

    /** 删除单条绑定 */
    @Transactional
    public void remove(String agentId, String kind, String name) {
        String aid = requireAgentId(agentId);
        String k = normalizeKind(kind);
        repository.findByAgentIdAndKindAndName(aid, k, name == null ? "" : name.trim())
                .ifPresent(repository::delete);
    }

    /** 某智能体某 kind 下「已启用」的能力名 */
    public List<String> enabledNames(String agentId, String kind) {
        String k = normalizeKind(kind);
        return repository.findByAgentIdAndKindOrderByEnabledDescNameAsc(requireAgentId(agentId), k).stream()
                .filter(AgentCapabilityEntity::isEnabled)
                .map(AgentCapabilityEntity::getName)
                .toList();
    }

    @Override
    public List<String> mcpServiceNames(String agentId) {
        return enabledNames(agentId, KIND_MCP);
    }

    @Override
    public List<String> mcpServiceNamesFor(Collection<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String agentId : agentIds) {
            if (agentId != null && !agentId.isBlank()) {
                ids.add(agentId.trim());
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return repository.findByAgentIdInAndKind(ids, KIND_MCP).stream()
                .filter(AgentCapabilityEntity::isEnabled)
                .map(AgentCapabilityEntity::getName)
                .distinct()
                .toList();
    }

    @Override
    public List<String> mcpToolNames(String agentId) {
        List<String> services = mcpServiceNames(agentId);
        // 展开失败（服务不存在/未连接）不能让子 Agent 白名单凭空变空——那等于静默失能，
        // 与「没绑定」的表现一致却更难排查。这里保留服务名并告警，由上层按不限制处理。
        if (services.isEmpty()) {
            return List.of();
        }
        Set<String> tools = mcpToolExpander.expand(services);
        if (tools.isEmpty()) {
            log.warn("智能体 [{}] 绑定了 MCP 服务 {} 但未展开出任何工具（服务未连接或已停用？）",
                    agentId, services);
        }
        return List.copyOf(tools);
    }

    private static String normalizeAgentId(String agentId) {
        return agentId == null ? "" : agentId.trim();
    }

    private static String requireAgentId(String agentId) {
        String aid = normalizeAgentId(agentId);
        if (aid.isEmpty()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        return aid;
    }

    private static String normalizeKind(String kind) {
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(k)) {
            throw new IllegalArgumentException("未知的能力类型: " + kind + "（可用: " + KINDS + "）");
        }
        return k;
    }
}
