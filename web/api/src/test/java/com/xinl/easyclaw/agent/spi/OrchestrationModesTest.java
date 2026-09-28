package com.xinl.easyclaw.agent.spi;

import com.xinl.easyclaw.base.orchestration.OrchestrationModes;
import com.xinl.easyclaw.scenario.ScenarioBinding;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * base 层静态 SPI 入口 {@link OrchestrationModes} 的验证。
 * <p>
 * <b>为什么单独测而不并入 {@code SpiRegistryTest}</b>：{@code OrchestrationModes} 是
 * 给<b>静态工具类</b>（{@code OrchestrationPromptBuilder}、{@code ScenarioBinding}）用的入口，
 * 它们注入不了 Spring Bean。这条路径与 Bean 门面是两套调用方，必须各自证明能用 ——
 * 若只有 Bean 侧有测试，静态侧退化成「永远返回 false」也不会被发现，
 * 表现为编排场景静默按单智能体跑。
 * <p>
 * 同时锁住「消除 {@code "team".equals} 硬编码后行为不变」这条等价性。
 */
class OrchestrationModesTest {

    @Test
    @DisplayName("静态入口与 Bean 门面看到同一份模式集合，不存在数据源分裂")
    void staticEntryAgreesWithBeanFacade() {
        OrchestratorRegistry bean = new OrchestratorRegistry();

        assertEquals(bean.modeIds(), OrchestrationModes.modeIds());
        assertEquals(bean.size(), OrchestrationModes.size());
    }

    @Test
    @DisplayName("isOrchestrated：single/ops 为假，team/schedule 为真，未知与空为假")
    void isOrchestratedSemantics() {
        assertFalse(OrchestrationModes.isOrchestrated("single"));
        assertTrue(OrchestrationModes.isOrchestrated("team"));
        assertTrue(OrchestrationModes.isOrchestrated("schedule"));

        // ops 是单执行体模式：计划恒为单阶段单步、不消费 workflow，
        // 不得被「编排型才有的配置校验 / 直派 / 审计」误伤（回归保护：
        // 曾因 isOrchestrated("ops")=true 导致 ops 场景保存被「需要至少一个工作流步骤」拒绝）。
        assertFalse(OrchestrationModes.isOrchestrated("ops"));

        // 未知 mode 视为非编排：与 resolve() 的降级方向一致，
        // 脏数据只会少走编排，不会让场景直接报错。
        assertFalse(OrchestrationModes.isOrchestrated("no-such-mode"));
        assertFalse(OrchestrationModes.isOrchestrated(null));
        assertFalse(OrchestrationModes.isOrchestrated("   "));
    }

    @Test
    @DisplayName("displayName 与改造前硬编码的文案逐字一致（提示词零变化）")
    void displayNameMatchesLegacyWording() {
        // 改造前："team".equals(mode) ? "多智能体协作" : "单智能体"
        assertEquals("单智能体", OrchestrationModes.displayNameOf("single"));
        assertEquals("多智能体协作", OrchestrationModes.displayNameOf("team"));

        // 改造前所有非 team 值（含 null / 未知）都落到 "单智能体"，此行为必须保留
        assertEquals("单智能体", OrchestrationModes.displayNameOf(null));
        assertEquals("单智能体", OrchestrationModes.displayNameOf("no-such-mode"));
    }

    @Test
    @DisplayName("ScenarioBinding 走静态入口后，team 仍判定为编排模式")
    void bindingDetectsOrchestratedMode() {
        assertTrue(ScenarioBinding.from(scenarioWithMode("team")).isOrchestratedMode());
        assertFalse(ScenarioBinding.from(scenarioWithMode("single")).isOrchestratedMode());
        assertFalse(ScenarioBinding.from(scenarioWithMode(null)).isOrchestratedMode());
    }

    @Test
    @DisplayName("空绑定（无激活场景）不得判为编排模式，否则子 Agent 步数会被误抬")
    void emptyBindingIsNotOrchestrated() {
        assertFalse(ScenarioBinding.EMPTY.isOrchestratedMode());
        assertFalse(ScenarioBinding.from(null).isOrchestratedMode());
    }

    @Test
    @DisplayName("withMcpTools 复制副本时必须保留编排模式标记")
    void withMcpToolsPreservesOrchestratedFlag() {
        ScenarioBinding origin = ScenarioBinding.from(scenarioWithMode("team"));

        assertTrue(origin.withMcpTools(java.util.List.of("x")).isOrchestratedMode(),
                "副本丢失编排标记会让 team 场景的子 Agent 退回默认步数");
    }

    private static ScenarioEntity scenarioWithMode(String mode) {
        ScenarioEntity e = new ScenarioEntity();
        e.setMode(mode);
        return e;
    }
}
