package com.xinl.easyclaw.agent.orchestrator;

import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OrchestrationPromptBuilder#build} 渲染测试。
 * <p>
 * 场景层的语义契约是「你处在什么环境 / 什么能做什么不能做 / 该用什么方法论」，
 * 对应固定三段式输出。这些断言锁定的是<b>结构</b>而非文案：段落缺失或顺序错乱
 * 会让模型读到割裂的上下文（历史上能力边界曾被拼在场景块之外，就是这个问题）。
 */
class OrchestrationPromptBuilderTest {

    private ScenarioEntity scenario(String mode, String description, String systemPrompt) {
        ScenarioEntity e = new ScenarioEntity();
        e.setName("s1");
        e.setDisplayName("通用编程");
        e.setIcon("💻");
        e.setMode(mode);
        e.setDescription(description);
        e.setSystemPrompt(systemPrompt);
        return e;
    }

    @Test
    @DisplayName("场景为 null 时返回 null，调用方据此跳过注入")
    void nullScenarioYieldsNull() {
        assertNull(OrchestrationPromptBuilder.build(null, List.of(), "边界"));
    }

    @Test
    @DisplayName("三段式：环境 / 能力边界 / 方法论 按序出现在同一场景标题下")
    void rendersThreeSectionsInOrder() {
        String out = OrchestrationPromptBuilder.build(
                scenario("single", "真实工程项目", "先读代码再动手"),
                List.of(), "- 本场景的 Skill：clean-code");

        int title = out.indexOf("## 🎬 当前场景：");
        int env = out.indexOf("### 你所处的环境");
        int boundary = out.indexOf("### 能力边界");
        int method = out.indexOf("### 工作方法论");

        assertTrue(title >= 0 && env > title, "环境段应在场景标题之后");
        assertTrue(boundary > env, "能力边界应在环境之后");
        assertTrue(method > boundary, "方法论应在能力边界之后");
        assertTrue(out.contains("真实工程项目"));
        assertTrue(out.contains("clean-code"));
        assertTrue(out.contains("先读代码再动手"));
    }

    @Test
    @DisplayName("single 模式标注「单智能体」，team 模式标注「多智能体协作」")
    void methodologyLabelReflectsMode() {
        assertTrue(OrchestrationPromptBuilder
                .build(scenario("single", "d", "p"), List.of(), null)
                .contains("### 工作方法论（单智能体）"));
        assertTrue(OrchestrationPromptBuilder
                .build(scenario("team", "d", "p"), List.of(), null)
                .contains("### 工作方法论（多智能体协作）"));
    }

    @Test
    @DisplayName("无能力边界时不渲染空的「能力边界」标题")
    void omitsBoundarySectionWhenAbsent() {
        String out = OrchestrationPromptBuilder.build(
                scenario("single", "d", "p"), List.of(), null);
        assertFalse(out.contains("### 能力边界"));

        String blank = OrchestrationPromptBuilder.build(
                scenario("single", "d", "p"), List.of(), "   ");
        assertFalse(blank.contains("### 能力边界"));
    }

    @Test
    @DisplayName("方法论段无内容时回退为「按基座闭环协议执行」，不留空标题")
    void fallsBackWhenMethodologyEmpty() {
        String out = OrchestrationPromptBuilder.build(
                scenario("single", "只有环境描述", null), List.of(), null);
        assertTrue(out.contains("本场景未定义专属方法论"));
    }

    @Test
    @DisplayName("team 模式：工作流编排计划落在方法论段内部，不另起顶级标题")
    void teamOrchestrationNestedUnderMethodology() {
        ScenarioEntity e = scenario("team", "团队环境", "你是编排者");
        e.setWorkflow("""
                {"steps":[{"role":"planner","instruction":"拆解需求","parallel":false}]}""");

        String out = OrchestrationPromptBuilder.build(
                e, List.of(decl("planner", "规划")), null);

        int method = out.indexOf("### 工作方法论");
        int plan = out.indexOf("角色编排工作流");
        assertTrue(plan > method, "编排计划应位于方法论段之内");
        assertTrue(out.contains("planner"));
        assertFalse(out.contains("无专属执行体"),
                "planner 已有同名执行体，不应标记");
    }

    @Test
    @DisplayName("team 模式：协调者被定位为「只分发+验收」，不亲自执行")
    void coordinatorIsDispatcherAndReviewerOnly() {
        ScenarioEntity e = scenario("team", "团队环境", "你是编排者");
        e.setWorkflow("""
                {"steps":[{"role":"coder","instruction":"实现"}]}""");

        String out = OrchestrationPromptBuilder.build(e, List.of(decl("coder", "实现")), null);
        assertTrue(out.contains("分发者"), "应声明协调者的分发职责");
        assertTrue(out.contains("验收"), "应声明协调者的验收职责");
        assertTrue(out.contains("返工"), "验收结论应包含返工分支");
        assertTrue(out.contains("不亲自干活") || out.contains("不要替他们"),
                "应明确协调者不亲自执行具体任务");
    }

    @Test
    @DisplayName("team 模式：同阶段多角色渲染为「同一轮同时派发」的并发指令")
    void parallelStageDeclaresConcurrentDispatch() {
        ScenarioEntity e = scenario("team", "团队环境", "你是编排者");
        e.setWorkflow("""
                {"steps":[{"role":"coder","instruction":"实现"},\
                {"role":"reviewer","instruction":"评审","parallel":true}]}""");

        String out = OrchestrationPromptBuilder.build(
                e, List.of(decl("coder", "实现"), decl("reviewer", "评审")), null);
        assertTrue(out.contains("2 个角色必须在同一轮同时派发"),
                "并行阶段应显式给出角色数与并发要求，实际输出:\n" + out);
    }

    @Test
    @DisplayName("team 模式：无专属执行体的角色被标注，但仍要求派发而非主控代劳")
    void marksRoleWithoutDedicatedExecutor() {
        ScenarioEntity e = scenario("team", "团队环境", "你是编排者");
        e.setWorkflow("""
                {"steps":[{"role":"ghost","instruction":"干活","parallel":false}]}""");

        String out = OrchestrationPromptBuilder.build(e, List.of(), null);
        assertTrue(out.contains("无专属执行体"));
        assertTrue(out.contains("仍要派发出去执行"), "不应回退成主控亲自动手");
    }

    @Test
    @DisplayName("team 模式：工作流为脏数据时降级为无编排计划，不阻断对话")
    void degradesOnBrokenWorkflow() {
        ScenarioEntity e = scenario("team", "团队环境", "你是编排者");
        e.setWorkflow("{not json");

        String out = OrchestrationPromptBuilder.build(e, List.of(), null);
        assertTrue(out.contains("你是编排者"), "场景方法论文本应保留");
        assertFalse(out.contains("角色编排工作流"), "非法工作流不应渲染计划");
    }

    @Test
    @DisplayName("标题回退：无 displayName 时用标识名，不输出空标题")
    void titleFallsBackToName() {
        ScenarioEntity e = scenario("single", "d", "p");
        e.setDisplayName(null);
        e.setIcon(null);
        assertTrue(OrchestrationPromptBuilder.build(e, List.of(), null)
                .startsWith("## 🎬 当前场景：s1"));
    }

    @Test
    @DisplayName("全空场景：仅有标题与方法论兜底，仍返回非空且可读")
    void emptyScenarioStillRenders() {
        ScenarioEntity e = scenario("single", null, null);
        String out = OrchestrationPromptBuilder.build(e, List.of(), null);
        assertEquals("## 🎬 当前场景：💻 通用编程\n\n### 工作方法论（单智能体）\n"
                + "本场景未定义专属方法论，按基座的任务闭环协议执行。", out);
    }

    private SubagentDeclaration decl(String name, String desc) {
        return SubagentDeclaration.builder()
                .name(name)
                .description(desc)
                .inlineAgentsBody("测试用内联定义")
                .build();
    }
}
