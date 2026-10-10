package com.xinl.easyclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SubagentLoader#blackboardGuide} 的格式渲染验证。
 * <p>
 * <b>这个测试类存在的理由</b>：黑板段用 {@code String.formatted} 注入步数与超时预算，
 * 一旦占位符数量与参数不匹配（多一个 {@code %} 或漏一个 {@code %d}）就会在运行时抛
 * {@link java.util.MissingFormatArgumentException}，而这类错误只在子 Agent 真正被拉起时
 * 才暴露。因此必须直接渲染一遍，断言两个预算都被真实数字替换、且无残留占位符。
 */
class BlackboardGuideFormatTest {

    @Test
    @DisplayName("blackboardGuide 渲染成功：两个预算占位符都被替换，无残留 %d")
    void blackboardGuideRendersBothBudgets() {
        String guide = SubagentLoader.blackboardGuide(30, 1800);

        assertThat(guide)
                .contains("本次任务你有 **30 步迭代** 的硬预算")
                .contains("同步超时参考约 **1800 秒**")
                .doesNotContain("%d")
                .doesNotContain("%s");
    }

    @Test
    @DisplayName("blackboardGuide 对边界预算同样能渲染")
    void blackboardGuideRendersBoundaryBudgets() {
        String guide = SubagentLoader.blackboardGuide(1, 1);
        assertThat(guide)
                .contains("**1 步迭代**")
                .contains("**1 秒**")
                .doesNotContain("%d");
    }
}