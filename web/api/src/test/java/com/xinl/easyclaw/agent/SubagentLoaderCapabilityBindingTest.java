package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.base.profile.ScenarioProfile;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.scenario.ScenarioBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * agent 级能力绑定如何参与<b>子 Agent 工具白名单</b>。
 * <p>
 * 这块逻辑的每个分支都对应一种「配了却没生效」或「配了反而更糟」的真实故障，因此逐条锁：
 * <ol>
 *   <li>场景绑了 MCP、agent 绑了其中一部分 → 取交集（agent 绑定用来指定，不用来放权）</li>
 *   <li>场景绑了 MCP、agent 没绑 → 场景全量（agent 绑定缺席不得收紧）</li>
 *   <li>场景只配档位没绑 MCP、agent 绑了 → agent 绑定独立生效（否则内置实例永远进不来）</li>
 *   <li>场景完全不裁剪、只有 agent 绑定 → <b>不裁剪</b>：凭空构造白名单会把子 Agent 原本
 *       继承的 read_file 等工具一并裁掉，表现为「配了绑定反而更不能用」</li>
 *   <li>没有绑定来源（无 Spring 上下文）→ 与改造前逐字节一致</li>
 * </ol>
 * {@code restrictTools} 是私有方法，用反射直测：走公开入口 {@code loadMerged} 需要完整
 * SPI 名册与 harness 装配，会把一个纯逻辑断言放大成集成测试。
 */
@DisplayName("agent 级 MCP 绑定与场景绑定的合成规则")
class SubagentLoaderCapabilityBindingTest {

    private static final String DB_TOOL = "db_query";
    private static final String OPS_TOOL = "remote_shell";

    /** 场景绑定：MCP 服务列表 + 是否显式配置档位 */
    private ScenarioBinding scenarioBinding(List<String> mcpServices, String tier,
                                            List<String> expandedTools) {
        ScenarioProfile profile = mock(ScenarioProfile.class);
        // ScenarioBinding.parseNameArray 支持逗号分隔兜底，无需拼 JSON 文本
        when(profile.getMcpServices()).thenReturn(mcpServices.isEmpty() ? null : String.join(",", mcpServices));
        when(profile.getCapabilityTier()).thenReturn(tier);
        when(profile.getMode()).thenReturn("single");
        return ScenarioBinding.from(profile).withMcpTools(expandedTools);
    }

    @SuppressWarnings("unchecked")
    private List<String> restrict(SubagentLoader loader, String agentId,
                                  List<String> declared, ScenarioBinding binding) throws Exception {
        Method method = SubagentLoader.class.getDeclaredMethod(
                "restrictTools", String.class, List.class, ScenarioBinding.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(loader, agentId, declared, binding);
    }

    private SubagentLoader loaderWith(Map<String, List<String>> toolsByAgent) {
        SubagentLoader loader = new SubagentLoader(mock(AgentScopeProperties.class));
        loader.setCapabilitySource(new AgentCapabilitySource() {
            @Override
            public List<String> mcpServiceNames(String agentId) {
                return List.of();
            }

            @Override
            public List<String> mcpServiceNamesFor(Collection<String> agentIds) {
                return List.of();
            }

            @Override
            public List<String> mcpToolNames(String agentId) {
                return toolsByAgent.getOrDefault(agentId, List.of());
            }
        });
        return loader;
    }

    @Test
    @DisplayName("场景绑 MCP + agent 绑其中一部分 → 取交集")
    void agentBindingNarrowsScenarioMcp() throws Exception {
        ScenarioBinding binding = scenarioBinding(List.of("svc-a"), null,
                List.of(DB_TOOL, OPS_TOOL, "db_schema"));
        SubagentLoader loader = loaderWith(Map.of("db", List.of(DB_TOOL, "db_schema")));

        List<String> tools = restrict(loader, "db", null, binding);

        assertThat(tools).contains(DB_TOOL, "db_schema");
        assertThat(tools).doesNotContain(OPS_TOOL);
    }

    @Test
    @DisplayName("场景绑 MCP + agent 无绑定 → 场景全量，不得收紧")
    void absentAgentBindingKeepsScenarioTools() throws Exception {
        ScenarioBinding binding = scenarioBinding(List.of("svc-a"), null, List.of(DB_TOOL, OPS_TOOL));
        SubagentLoader loader = loaderWith(Map.of());

        // 裁剪时档位基础工具（STANDARD 的 read_file 等）本就会并入白名单，故只断言 MCP 工具全量保留
        assertThat(restrict(loader, "db", null, binding)).contains(DB_TOOL, OPS_TOOL);
    }

    @Test
    @DisplayName("场景只配档位未绑 MCP + agent 绑了 → agent 绑定独立生效")
    void agentBindingWorksWhenScenarioHasNoMcp() throws Exception {
        ScenarioBinding binding = scenarioBinding(List.of(), "readonly", List.of());
        SubagentLoader loader = loaderWith(Map.of("db", List.of(DB_TOOL)));

        List<String> tools = restrict(loader, "db", null, binding);

        // 档位基础工具（read_file 等）必须在，否则子 Agent 连文件都读不了
        assertThat(tools).contains(DB_TOOL);
        assertThat(tools).contains("read_file");
    }

    @Test
    @DisplayName("场景不裁剪 + 仅 agent 绑定 → 原样返回声明值（不凭空构造白名单）")
    void noScenarioShrinkMeansNoShrinkAtAll() throws Exception {
        ScenarioBinding binding = scenarioBinding(List.of(), null, List.of());
        SubagentLoader loader = loaderWith(Map.of("db", List.of(DB_TOOL)));
        List<String> declared = List.of("read_file", "grep_files");

        assertThat(restrict(loader, "db", declared, binding)).containsExactlyElementsOf(declared);
    }

    @Test
    @DisplayName("无绑定来源（未注入）→ 与改造前一致")
    void nullSourceKeepsLegacyBehaviour() throws Exception {
        SubagentLoader loader = new SubagentLoader(mock(AgentScopeProperties.class));
        ScenarioBinding binding = scenarioBinding(List.of("svc-a"), null, List.of(DB_TOOL, OPS_TOOL));

        // 裁剪时档位基础工具（STANDARD 的 read_file 等）本就会并入白名单，故只断言 MCP 工具全量保留
        assertThat(restrict(loader, "db", null, binding)).contains(DB_TOOL, OPS_TOOL);
    }

    @Test
    @DisplayName("agent 绑定与场景 MCP 无交集 → 回退场景全量并保留档位工具")
    void disjointBindingFallsBackToScenario() throws Exception {
        ScenarioBinding binding = scenarioBinding(List.of("svc-a"), null, List.of(DB_TOOL));
        SubagentLoader loader = loaderWith(Map.of("db", List.of("totally_other")));

        assertThat(restrict(loader, "db", null, binding)).contains(DB_TOOL);
    }
}
