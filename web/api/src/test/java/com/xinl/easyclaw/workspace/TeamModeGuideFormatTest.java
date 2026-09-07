package com.xinl.easyclaw.workspace;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@code subagentRoster} 的提示词模板在运行时能正确渲染。
 * <p>
 * 存在意义：该方法用 {@code .formatted(minSteps)} 渲染一大段中文提示词，
 * 文本里任何一个游离的 {@code %}（比如后人补一句「命中率 95%」）都会在
 * <b>运行时</b>抛 {@code UnknownFormatConversionException} —— 编译期发现不了，
 * 而失败点位于 Agent 构建路径上，会直接让整个工作区起不来。
 * <p>
 * 这里用反射直调私有方法，绕开 Builder 的 Spring 依赖：本测试只关心文本渲染，
 * 拉起整个上下文既慢又会把失败原因掺进无关的装配问题。
 */
class TeamModeGuideFormatTest {

    @Test
    @SuppressWarnings("unchecked")
    void subagentRosterRendersWithoutFormatError() throws Exception {
        Constructor<WorkspaceAgentBuilder> ctor =
                (Constructor<WorkspaceAgentBuilder>)
                        WorkspaceAgentBuilder.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        // 其余依赖传 null：subagentRoster 只读名册与 properties，不碰其他 Bean。
        Object[] args = new Object[ctor.getParameterCount()];
        WorkspaceAgentBuilder builder = ctor.newInstance(args);

        // AgentScopeProperties 必须注入真实对象：subagentRoster 里
        // `.orElse(properties.getAgent().getSubagentSteps())` 是及早求值——
        // 即使名册里已有 steps，orElse 的参数也会先被计算，null 会直接 NPE。
        java.lang.reflect.Field propsField =
                WorkspaceAgentBuilder.class.getDeclaredField("agentScopeProperties");
        propsField.setAccessible(true);
        propsField.set(builder, new com.xinl.easyclaw.config.AgentScopeProperties());

        SubagentDeclaration d = SubagentDeclaration.builder()
                .name("coder")
                .description("代码实现专家")
                .steps(15)
                .build();

        Method m = WorkspaceAgentBuilder.class
                .getDeclaredMethod("subagentRoster", List.class, boolean.class);
        m.setAccessible(true);
        String teamGuide = (String) m.invoke(builder, List.of(d), true);
        String singleGuide = (String) m.invoke(builder, List.of(d), false);

        for (String guide : List.of(teamGuide, singleGuide)) {
            // 渲染成功且占位符被真实替换
            assertTrue(guide.contains("当前 15 步"), "minSteps 应被渲染为名册里的实际步数");
            // 模板里不应残留任何未消费的格式化占位符
            assertFalse(guide.contains("%d"), "不应残留未替换的 %d");
            // 机制类规则两个模式共用
            assertTrue(guide.contains("任务最小化"), "应包含任务最小化规则");
            assertTrue(guide.contains("【交付物】"), "应包含交付物模板");
            assertTrue(guide.contains("load_skill_through_path"), "应包含 skill 指定规则");
            assertTrue(guide.contains("角色名-阶段号"), "应包含 label 命名约定");
            // 编排类规则已下沉到 OrchestrationPromptBuilder，名册层不得再出现，
            // 否则 team 模式下模型会同时收到两套自称最终准则的行为规范而摇摆。
            assertFalse(guide.contains("动态组建团队"), "编排规则不应出现在名册层");
            assertFalse(guide.contains("你负责收口"), "协调者定位不应出现在名册层");
        }
        // 两个模式的开场白不同：single 明确「简单任务自己做」，team 不含该让步
        assertTrue(singleGuide.contains("简单任务自己做"), "single 模式应提示不必为用而用");
        assertFalse(teamGuide.contains("简单任务自己做"), "team 模式不应出现自己动手的让步");
    }
}
