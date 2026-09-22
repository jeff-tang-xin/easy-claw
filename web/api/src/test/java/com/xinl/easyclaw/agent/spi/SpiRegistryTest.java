package com.xinl.easyclaw.agent.spi;

import com.xinl.easyclaw.base.agent.EasyClawAgent;
import com.xinl.easyclaw.base.orchestration.AgentOrchestrator;
import com.xinl.easyclaw.base.orchestration.OrchestrationPlan;
import com.xinl.easyclaw.base.profile.ScenarioProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPI 装配的端到端验证。
 * <p>
 * <b>为什么必须有这组测试</b>：ServiceLoader 的失败方式是「静默返回空集合」——
 * {@code META-INF/services} 文件名写错一个字符、类名拼错、模块没进 classpath，
 * 全都表现为「一个实现都没发现」而不报错。编译通过完全无法证明装配成功，
 * 只有真的 load 出来并数一遍才算数。
 */
class SpiRegistryTest {

    /** 期望注册的 8 个平级智能体（ops 为运维模式专属主控，2026-09 新增） */
    private static final List<String> EXPECTED_AGENTS = List.of(
            "main", "coder", "reviewer", "planner",
            "researcher", "code-expert", "file-expert", "ops");

    /** 期望注册的 4 种编排模式（ops 为单执行体模式：isOrchestrated=false 但 SPI 可发现） */
    private static final List<String> EXPECTED_MODES = List.of("single", "team", "schedule", "ops");

    @Test
    @DisplayName("8 个智能体全部能被 ServiceLoader 发现")
    void discoversAllAgents() {
        AgentRegistry registry = new AgentRegistry();

        assertEquals(EXPECTED_AGENTS.size(), registry.size(),
                "实际发现: " + registry.agentIds());
        for (String id : EXPECTED_AGENTS) {
            assertTrue(registry.contains(id), "未发现智能体: " + id);
        }
    }

    @Test
    @DisplayName("4 种编排模式全部能被 ServiceLoader 发现")
    void discoversAllModes() {
        OrchestratorRegistry registry = new OrchestratorRegistry();

        assertEquals(EXPECTED_MODES.size(), registry.size(),
                "实际发现: " + registry.modeIds());
        for (String id : EXPECTED_MODES) {
            assertTrue(registry.find(id).isPresent(), "未发现编排模式: " + id);
        }
    }

    @Test
    @DisplayName("每个智能体的契约方法都不返回 null，且 agentId 与 SPI 键一致")
    void agentContractsAreSound() {
        AgentRegistry registry = new AgentRegistry();

        for (EasyClawAgent agent : registry.all()) {
            String id = agent.agentId();
            assertNotNull(agent.profile(), id + ".profile() 为 null");
            assertNotNull(agent.toolPolicy(), id + ".toolPolicy() 为 null");
            assertNotNull(agent.skillPolicy(), id + ".skillPolicy() 为 null");
            assertNotNull(agent.modelPreference(), id + ".modelPreference() 为 null");
            assertNotNull(agent.dispatchPolicy(), id + ".dispatchPolicy() 为 null");
            // ctx 为 null 时不得抛异常：无场景绑定是合法状态
            assertNotNull(agent.prompt(null), id + ".prompt(null) 为 null");
            // 步数下限守住 30，低于此值子 Agent 会被静默截断成半成品
            assertTrue(agent.stepFloor() >= 30,
                    id + ".stepFloor()=" + agent.stepFloor() + " 低于产品下限 30");
        }
    }

    @Test
    @DisplayName("智能体的派遣目标必须都是真实存在的 agentId")
    void dispatchTargetsAreRegistered() {
        AgentRegistry registry = new AgentRegistry();

        for (EasyClawAgent agent : registry.all()) {
            for (String target : agent.dispatchPolicy().allowedTargets()) {
                assertTrue(registry.contains(target),
                        agent.agentId() + " 声明可派遣 [" + target + "]，但该智能体未注册");
            }
        }
    }

    @Test
    @DisplayName("mode 为空或未知时降级为 single，不抛异常")
    void resolveFallsBackToSingle() {
        OrchestratorRegistry registry = new OrchestratorRegistry();

        assertEquals("single", registry.resolve(null).modeId());
        assertEquals("single", registry.resolve(scenario(null)).modeId());
        assertEquals("single", registry.resolve(scenario("  ")).modeId());
        assertEquals("single", registry.resolve(scenario("no-such-mode")).modeId());
    }

    @Test
    @DisplayName("存量 mode 字面量 single/team 必须仍能命中（DB 兼容红线）")
    void legacyModeLiteralsStillResolve() {
        OrchestratorRegistry registry = new OrchestratorRegistry();

        assertEquals("single", registry.resolve(scenario("single")).modeId());
        assertEquals("team", registry.resolve(scenario("team")).modeId());
    }

    @Test
    @DisplayName("isOrchestrated 按「非 single」判定，新增模式无需改判断逻辑")
    void isOrchestratedCoversNewModes() {
        OrchestratorRegistry registry = new OrchestratorRegistry();

        assertFalse(registry.isOrchestrated(scenario("single")));
        assertTrue(registry.isOrchestrated(scenario("team")));
        assertTrue(registry.isOrchestrated(scenario("schedule")));
    }

    @Test
    @DisplayName("registry 是每实例固化的：同一实例多次查询返回同一对象")
    void lookupIsStableWithinInstance() {
        AgentRegistry registry = new AgentRegistry();

        assertSame(registry.find("coder").orElseThrow(),
                registry.find("coder").orElseThrow());
    }

    @Test
    @DisplayName("team 模式缺场景时返回带错误的计划，而不是抛异常")
    void teamWithoutScenarioReturnsFailedPlan() {
        OrchestratorRegistry registry = new OrchestratorRegistry();
        AgentOrchestrator team = registry.find("team").orElseThrow();

        OrchestrationPlan plan = team.plan(
                com.xinl.easyclaw.base.orchestration.ExecutionContext.of("ws-1", null, "随便什么任务"));

        assertFalse(plan.executable(), "无场景的 team 计划不应可执行");
        assertFalse(plan.errors().isEmpty(), "应给出明确错误说明");
    }

    /**
     * 只关心 mode 的测试替身。
     * <p>
     * {@code ScenarioProfile} 有 12 个抽象方法，但模式解析只读 {@code getMode()}；
     * 用 record 一次性把其余方法钉死为 null，避免每个用例写一堆无意义的 override。
     */
    private record ModeOnlyScenario(String mode) implements ScenarioProfile {
        @Override
        public String getMode() {
            return mode;
        }

        @Override
        public String getName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getIcon() {
            return null;
        }

        @Override
        public String getDescription() {
            return null;
        }

        @Override
        public String getSystemPrompt() {
            return null;
        }

        @Override
        public String getWorkflow() {
            return null;
        }

        @Override
        public String getSkills() {
            return null;
        }

        @Override
        public String getSubagents() {
            return null;
        }

        @Override
        public String getMcpServices() {
            return null;
        }

        @Override
        public String getCapabilityTier() {
            return null;
        }

        @Override
        public String getRoleName() {
            return null;
        }
    }

    private static ScenarioProfile scenario(String mode) {
        return new ModeOnlyScenario(mode);
    }
}
