package com.xinl.easyclaw.workspace;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@link SystemPromptComposer#subagentRoster} 的提示词模板在运行时能正确渲染。
 * <p>
 * 存在意义：该方法用 {@code .formatted(minSteps)} 渲染一大段中文提示词，
 * 文本里任何一个游离的 {@code %}（比如后人补一句「命中率 95%」）都会在
 * <b>运行时</b>抛 {@code UnknownFormatConversionException} —— 编译期发现不了，
 * 而失败点位于 Agent 构建路径上，会直接让整个工作区起不来。
 * <p>
 * 该方法现为 {@code SystemPromptComposer} 的包级静态方法、fallback 步数由参数传入，
 * 因此本测试直接调用，无需反射、无需拉起 Spring 装配。
 */
class TeamModeGuideFormatTest {

    private SubagentDeclaration coder() {
        return SubagentDeclaration.builder()
                .name("coder")
                .description("代码实现专家")
                .steps(15)
                .build();
    }

    private void assertCommonGuides(String guide) {
        // 模板里不应残留任何未消费的格式化占位符
        assertFalse(guide.contains("%d"), "不应残留未替换的 %d");
        // 机制类规则两个模式共用
        assertTrue(guide.contains("任务最小化"), "应包含任务最小化规则");
        assertTrue(guide.contains("【交付物】"), "应包含交付物模板");
        assertTrue(guide.contains("load_skill_through_path"), "应包含 skill 指定规则");
        assertTrue(guide.contains("agentId-阶段号"), "应包含 label 命名约定");
        // 编排类规则已下沉到 OrchestrationPromptBuilder，名册层不得再出现，
        // 否则 team 模式下模型会同时收到两套自称最终准则的行为规范而摇摆。
        assertFalse(guide.contains("动态组建团队"), "编排规则不应出现在名册层");
        assertFalse(guide.contains("你负责收口"), "协调者定位不应出现在名册层");
    }

    @Test
    void subagentRosterRendersWithoutFormatError() {
        String teamGuide = SystemPromptComposer.subagentRoster(List.of(coder()), true, 30);
        String singleGuide = SystemPromptComposer.subagentRoster(List.of(coder()), false, 30);

        // 渲染成功且占位符被名册里的真实步数替换
        assertTrue(teamGuide.contains("当前 15 步"), "minSteps 应被渲染为名册里的实际步数");
        assertTrue(singleGuide.contains("当前 15 步"), "minSteps 应被渲染为名册里的实际步数");
        assertCommonGuides(teamGuide);
        assertCommonGuides(singleGuide);

        // 两个模式的开场白不同：single 明确「简单任务自己做」，team 不含该让步
        assertTrue(singleGuide.contains("简单任务自己做"), "single 模式应提示不必为用而用");
        assertFalse(teamGuide.contains("简单任务自己做"), "team 模式不应出现自己动手的让步");
    }

    @Test
    void emptyRosterFallsBackToConfiguredSteps() {
        // 名册为空时 min() 缺省，步数取参数传入的 yml 兜底值而非 NPE。
        String guide = SystemPromptComposer.subagentRoster(List.of(), false, 42);
        assertTrue(guide.contains("当前 42 步"), "空名册应使用配置的兜底步数");
        assertFalse(guide.contains("%d"), "不应残留未替换的 %d");
    }
}
