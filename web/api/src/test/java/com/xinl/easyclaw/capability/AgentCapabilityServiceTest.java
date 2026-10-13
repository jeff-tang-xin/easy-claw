package com.xinl.easyclaw.capability;

import com.xinl.easyclaw.scenario.McpToolExpander;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * agent 级能力绑定的读写语义。
 * <p>
 * 这里锁的是「页面勾选最终变成什么」：绑定是纯配置数据，一旦语义漂移（去重方向、
 * enabled 过滤、kind 归一），下游 toolkit 与子 Agent 白名单会一起跟着错，
 * 而且错得很安静——用户看到的就是「勾了没反应」。
 */
@DisplayName("智能体能力绑定：整组覆盖与启用过滤")
class AgentCapabilityServiceTest {

    private AgentCapabilityRepository repository;
    private McpToolExpander expander;
    private AgentCapabilityService service;

    @BeforeEach
    void setUp() {
        repository = mock(AgentCapabilityRepository.class);
        expander = mock(McpToolExpander.class);
        service = new AgentCapabilityService(repository, expander);
    }

    private static AgentCapabilityEntity entity(String agentId, String kind, String name, boolean enabled) {
        return AgentCapabilityEntity.builder()
                .agentId(agentId).kind(kind).name(name).enabled(enabled)
                .build();
    }

    @Test
    @DisplayName("整组覆盖：先删后建，空清单等价于清空")
    void replaceClearsThenWrites() {
        service.replace("db", "mcp", List.of());

        verify(repository).deleteByAgentIdAndKind("db", "mcp");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("同名去重：一次提交里先勾后取消，按「取消」生效")
    void duplicateNameLastWins() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<AgentCapabilityEntity> saved = service.replace("db", "mcp", List.of(
                new AgentCapabilityService.Binding("prod-orders", Boolean.TRUE),
                new AgentCapabilityService.Binding("prod-orders", Boolean.FALSE)));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("enabled 缺省视为启用：前端只传名字也能落库")
    void nullEnabledDefaultsToTrue() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<AgentCapabilityEntity> saved = service.replace("db", "mcp",
                List.of(new AgentCapabilityService.Binding("prod-orders", null)));

        assertThat(saved.get(0).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("空白名称直接丢弃，不产生脏绑定行")
    void blankNamesIgnored() {
        service.replace("db", "mcp", List.of(
                new AgentCapabilityService.Binding("  ", Boolean.TRUE),
                new AgentCapabilityService.Binding(null, Boolean.TRUE)));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("kind 归一：MCP / mcp 视为同一组，agentId 去空格")
    void kindAndAgentIdNormalized() {
        service.replace("  db  ", "MCP", List.of());

        verify(repository).deleteByAgentIdAndKind("db", "mcp");
    }

    @Test
    @DisplayName("agentId 空白直接拒绝：否则会写出一条谁也读不到的绑定")
    void blankAgentIdRejected() {
        assertThatThrownBy(() -> service.replace("  ", "mcp", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("启用过滤：已停用的绑定不进白名单")
    void enabledNamesFiltersDisabled() {
        when(repository.findByAgentIdAndKindOrderByEnabledDescNameAsc("db", "mcp"))
                .thenReturn(List.of(
                        entity("db", "mcp", "prod-orders", true),
                        entity("db", "mcp", "stale-svc", false)));

        assertThat(service.mcpServiceNames("db")).containsExactly("prod-orders");
    }

    @Test
    @DisplayName("并集去重：主控与子 Agent 绑同一服务只出现一次")
    void unionAcrossAgentsDeduplicates() {
        when(repository.findByAgentIdInAndKind(any(), eq("mcp"))).thenReturn(List.of(
                entity("main", "mcp", "prod-orders", true),
                entity("db", "mcp", "prod-orders", true),
                entity("db", "mcp", "report-db", true),
                entity("db", "mcp", "disabled-one", false)));

        // Arrays.asList 而非 List.of：后者禁 null 元素，而这里要正是要喂 null 验证容错分支
        assertThat(service.mcpServiceNamesFor(Arrays.asList("main", "db", " ", null)))
                .containsExactly("prod-orders", "report-db");
    }

    @Test
    @DisplayName("无 id 时不查库：避免全表扫描把别人的绑定并进来")
    void emptyIdsShortCircuits() {
        assertThat(service.mcpServiceNamesFor(List.of())).isEmpty();
        verify(repository, never()).findByAgentIdInAndKind(any(), anyString());
    }

    @Test
    @DisplayName("删除单条：按 (agentId, kind, name) 精确定位")
    void removeDeletesSingleRow() {
        AgentCapabilityEntity target = entity("db", "mcp", "prod-orders", true);
        when(repository.findByAgentIdAndKindAndName("db", "mcp", "prod-orders"))
                .thenReturn(Optional.of(target));

        service.remove("db", "mcp", "prod-orders");

        verify(repository).delete(target);
    }
}
