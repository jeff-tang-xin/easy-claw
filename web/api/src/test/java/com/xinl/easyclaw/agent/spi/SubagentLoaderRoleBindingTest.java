package com.xinl.easyclaw.agent.spi;

import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.scenario.ScenarioBinding;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 方案 C（角色系统下线）后 SPI 路径的<b>人格与模型解析</b>行为验证。
 * <p>
 * 角色系统下线后，{@code SubagentLoader} 完全不依赖 DB 角色：
 * <ul>
 *   <li>人格恒由 SPI 内置 {@code builtinPersona()} 提供；</li>
 *   <li>模型优先级为 SPI {@code modelPreference()} → {@code application.yml} 的
 *       {@code agents.&lt;id&gt;}（{@link AgentScopeProperties#resolveAgentModel}）→ 不设置跟随全局。</li>
 * </ul>
 * 取 coder 作样本：它内置人格含稳定文案，且 {@code modelPreference()} 恒为 DEFAULT。
 */
class SubagentLoaderRoleBindingTest {

    private SubagentDeclaration loadCoder(AgentScopeProperties props) {
        return new SubagentLoader(props, new AgentRegistry())
                .loadMerged(ScenarioBinding.EMPTY).stream()
                .filter(d -> "coder".equals(d.getName()))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("人格恒为 SPI 内置文案：装配不再依赖任何 DB 角色来源")
    void personaAlwaysComesFromSpiBuiltin() {
        SubagentDeclaration coder = loadCoder(new AgentScopeProperties());

        String prompt = coder.getInlineAgentsBody();
        // SPI 内置人格的稳定文案，且身份段先于黑板/交付纪律等程序化约定
        assertThat(prompt).contains("代码实现专家，把明确的任务指令转化为可运行");
        assertThat(prompt.indexOf("代码实现专家，把明确的任务指令转化为可运行"))
                .isLessThan(prompt.indexOf("blackboard_append"));
    }

    @Test
    @DisplayName("模型：SPI 无偏好时取 yml agents.<id>.model-name；未配置则保持 null 由父模型兜底")
    void modelFallsBackToYmlConfigAndKeepsNullWhenUnset() {
        // 1. yml 为 coder 配置模型 → 写入声明
        AgentScopeProperties withYml = new AgentScopeProperties();
        AgentScopeProperties.AgentModelConfig cfg = new AgentScopeProperties.AgentModelConfig();
        cfg.setModelName("qwen3-max");
        withYml.setAgents(Map.of("coder", cfg));
        assertThat(loadCoder(withYml).getModel()).isEqualTo("qwen3-max");

        // 2. yml 未配置 coder → model 保持 null，由 harness 跟随父模型
        assertThat(loadCoder(new AgentScopeProperties()).getModel())
                .as("未配置 per-agent 模型时不得写入声明，否则会覆盖掉父模型")
                .isNull();
    }
}
