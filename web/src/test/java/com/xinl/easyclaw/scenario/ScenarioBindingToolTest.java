package com.xinl.easyclaw.scenario;

import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScenarioBinding} 工具白名单语义测试。
 * <p>
 * 核心防线是「未显式配置档位时绝不裁剪工具」：档位默认值是 STANDARD，
 * 若把「解析出 STANDARD」与「用户配了 STANDARD」混为一谈，所有既有场景的
 * 子智能体都会突然失去 execute / 子 Agent 调度能力，而日志上看不出任何异常。
 */
class ScenarioBindingToolTest {

    private static ScenarioEntity scenario() {
        ScenarioEntity s = new ScenarioEntity();
        s.setName("t");
        s.setMode("single");
        return s;
    }

    @Test
    void 未配置档位时不应触发工具裁剪() {
        ScenarioBinding b = ScenarioBinding.from(scenario());

        assertThat(b.hasExplicitTier()).isFalse();
        assertThat(b.hasToolBinding()).isFalse();
        assertThat(b.isEmpty()).isTrue();
        // 默认解析值仍是 STANDARD，但不得因此被当成「已配置」
        assertThat(b.tier()).isEqualTo(CapabilityTier.STANDARD);
    }

    @Test
    void 空白档位字符串等同于未配置() {
        ScenarioEntity s = scenario();
        s.setCapabilityTier("   ");

        ScenarioBinding b = ScenarioBinding.from(s);

        assertThat(b.hasExplicitTier()).isFalse();
        assertThat(b.hasToolBinding()).isFalse();
    }

    @Test
    void 显式配置与默认值相同的档位仍应触发裁剪() {
        ScenarioEntity s = scenario();
        s.setCapabilityTier("standard");

        ScenarioBinding b = ScenarioBinding.from(s);

        assertThat(b.hasExplicitTier()).isTrue();
        assertThat(b.hasToolBinding()).isTrue();
        assertThat(b.isEmpty()).isFalse();
    }

    @Test
    void 仅绑定MCP也应触发工具裁剪() {
        ScenarioEntity s = scenario();
        s.setMcpServices("[\"fs\"]");

        ScenarioBinding b = ScenarioBinding.from(s);

        assertThat(b.hasExplicitTier()).isFalse();
        assertThat(b.hasToolBinding()).isTrue();
    }

    @Test
    void withMcpTools应返回副本且不改动原对象() {
        ScenarioEntity s = scenario();
        s.setCapabilityTier("readonly");
        ScenarioBinding origin = ScenarioBinding.from(s);

        ScenarioBinding derived = origin.withMcpTools(List.of("fs_read", "fs_write"));

        assertThat(origin.mcpTools()).isEmpty();
        assertThat(derived.mcpTools()).containsExactly("fs_read", "fs_write");
        // 其余字段必须完整承接，否则派生对象会悄悄丢掉档位约束
        assertThat(derived.tier()).isEqualTo(CapabilityTier.READONLY);
        assertThat(derived.hasExplicitTier()).isTrue();
    }

    @Test
    void withMcpTools传null应安全降级为空集() {
        ScenarioBinding b = ScenarioBinding.from(scenario()).withMcpTools(null);

        assertThat(b.mcpTools()).isEmpty();
    }

    @Test
    void mcpTools应不可变防止外部篡改白名单() {
        ScenarioBinding b = ScenarioBinding.from(scenario()).withMcpTools(List.of("a"));

        assertThat(b.mcpTools()).isUnmodifiable();
    }
}
