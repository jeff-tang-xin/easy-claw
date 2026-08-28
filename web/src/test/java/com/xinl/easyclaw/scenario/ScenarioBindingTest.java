package com.xinl.easyclaw.scenario;

import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScenarioBinding} 解析行为测试。
 * <p>
 * 这几列在数据库里是裸 TEXT，写入方可能是前端 API（标准 JSON 数组）、
 * 早期版本（裸字符串），也可能是人工直接 UPDATE（逗号分隔）。
 * 解析必须对这些形态全部宽容，且任何脏数据都不能抛异常
 * —— 一旦抛出，工作区就起不来了。
 */
class ScenarioBindingTest {

    private ScenarioEntity scenario(String skills, String subagents,
                                    String mcpServices, String tier) {
        ScenarioEntity e = new ScenarioEntity();
        e.setSkills(skills);
        e.setSubagents(subagents);
        e.setMcpServices(mcpServices);
        e.setCapabilityTier(tier);
        return e;
    }

    @Test
    void 标准JSON数组应被解析() {
        ScenarioBinding b = ScenarioBinding.from(scenario(
                "[\"clean-code\",\"code-refactor\"]", "[\"coder\"]", "[\"filesystem\"]", "full"));

        assertThat(b.skills()).containsExactly("clean-code", "code-refactor");
        assertThat(b.subagents()).containsExactly("coder");
        assertThat(b.mcpServices()).containsExactly("filesystem");
        assertThat(b.tier()).isEqualTo(CapabilityTier.FULL);
    }

    @Test
    void 逗号分隔的非JSON文本应兜底解析() {
        ScenarioBinding b = ScenarioBinding.from(
                scenario("clean-code, code-refactor", null, null, null));

        assertThat(b.skills()).containsExactly("clean-code", "code-refactor");
    }

    @Test
    void null场景应返回不限制的空绑定() {
        ScenarioBinding b = ScenarioBinding.from(null);

        assertThat(b.isEmpty()).isTrue();
        assertThat(b.hasMcpBinding()).isFalse();
        assertThat(b.hasSkillBinding()).isFalse();
    }

    @Test
    void 全部列为空时应视为不限制() {
        ScenarioBinding b = ScenarioBinding.from(scenario(null, "", "   ", null));

        assertThat(b.isEmpty()).isTrue();
        // 未指定档位时回退默认档，而不是 null
        assertThat(b.tier()).isEqualTo(CapabilityTier.STANDARD);
    }

    @Test
    void 重复项应去重且保持首次出现顺序() {
        ScenarioBinding b = ScenarioBinding.from(
                scenario("[\"a\",\"b\",\"a\"]", null, null, null));

        assertThat(b.skills()).containsExactly("a", "b");
    }

    @Test
    void 数组中的空串与空白项应被丢弃() {
        ScenarioBinding b = ScenarioBinding.from(
                scenario("[\"a\",\"\",\"  \",\"b\"]", null, null, null));

        assertThat(b.skills()).containsExactly("a", "b");
    }

    @Test
    void 非法档位名应回退默认档而非抛异常() {
        ScenarioBinding b = ScenarioBinding.from(scenario(null, null, null, "不存在的档位"));

        assertThat(b.tier()).isEqualTo(CapabilityTier.STANDARD);
    }

    @Test
    void 数字或对象等非预期JSON不应抛异常() {
        assertThat(ScenarioBinding.from(scenario("123", null, null, null)).skills()).isEmpty();
        assertThat(ScenarioBinding.from(scenario("{\"k\":\"v\"}", null, null, null)).skills())
                .isEmpty();
    }

    @Test
    void 只绑定MCP时hasMcpBinding为真且非空绑定() {
        ScenarioBinding b = ScenarioBinding.from(
                scenario(null, null, "[\"filesystem\"]", null));

        assertThat(b.hasMcpBinding()).isTrue();
        assertThat(b.hasSkillBinding()).isFalse();
        assertThat(b.isEmpty()).isFalse();
    }

    @Test
    void 返回的列表应不可修改防止调用方污染绑定() {
        ScenarioBinding b = ScenarioBinding.from(scenario("[\"a\"]", null, null, null));

        assertThat(b.skills()).isUnmodifiable();
        assertThat(b.subagents()).isUnmodifiable();
        assertThat(b.mcpServices()).isUnmodifiable();
    }
}
