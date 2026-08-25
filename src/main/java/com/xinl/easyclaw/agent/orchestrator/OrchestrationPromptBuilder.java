package com.xinl.easyclaw.agent.orchestrator;

import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 多智能体编排提示构建器
 * <p>
 * 把激活的 {@link ScenarioEntity}（场景提示词 + team 工作流）翻译成
 * 注入主智能体 system prompt 的编排指令：
 * <ul>
 *   <li>single 模式：只注入场景人格/规范提示词</li>
 *   <li>team 模式：在场景提示词之上，把工作流步骤组织成「阶段（串行）/ 分组（并行）」
 *       的执行计划，主智能体作为编排者按计划调度子 Agent 并汇总结果</li>
 * </ul>
 * 解析与校验职责已下沉到 {@link WorkflowParser}，本类只负责渲染。
 * <p>
 * team 模式会要求主智能体在回复末尾输出 {@code <orchestration-audit .../>} 审计行，
 * 供 {@link OrchestrationAuditVerifier} 校验「计划是否真的被执行」。
 */
public final class OrchestrationPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationPromptBuilder.class);

    private OrchestrationPromptBuilder() {
    }

    /**
     * 构建场景 augment（场景未激活或内容为空时返回 null）
     *
     * @param scenario   激活的场景（可为 null）
     * @param subagents  当前 Agent 已注册的子 Agent 声明（用于成员校验）
     */
    public static String build(ScenarioEntity scenario, List<SubagentDeclaration> subagents) {
        if (scenario == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 🎬 当前场景：").append(displayName(scenario)).append("\n");
        if (notBlank(scenario.getDescription())) {
            sb.append(scenario.getDescription().trim()).append("\n");
        }
        if (notBlank(scenario.getSystemPrompt())) {
            sb.append("\n### 场景行为规范（必须遵守）\n")
                    .append(scenario.getSystemPrompt().trim()).append("\n");
        }
        if ("team".equals(scenario.getMode())) {
            String orchestration = buildTeamOrchestration(scenario, subagents);
            if (orchestration != null) {
                sb.append("\n").append(orchestration);
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * team 模式：把工作流步骤编译成「阶段/并行组」执行计划提示词。
     */
    private static String buildTeamOrchestration(ScenarioEntity scenario,
                                                 List<SubagentDeclaration> subagents) {
        WorkflowParseResult parsed = WorkflowParser.parse(scenario.getWorkflow());
        if (!parsed.ok()) {
            // 已落库的历史脏数据：不阻断对话，降级为「无编排计划」并告警
            log.warn("场景[{}] 工作流非法，已跳过编排注入: {}", scenario.getName(), parsed.errorMessage());
            return null;
        }
        if (!parsed.hasSteps()) {
            return null;
        }
        for (String warning : parsed.warnings()) {
            log.warn("场景[{}] 工作流告警: {}", scenario.getName(), warning);
        }

        Set<String> available = new LinkedHashSet<>();
        for (SubagentDeclaration d : subagents) {
            available.add(d.getName());
        }
        List<List<WorkflowStep>> groups = WorkflowParser.groupByStage(parsed.steps());

        StringBuilder sb = new StringBuilder();
        sb.append("### 🤝 多智能体编排工作流（本场景的任务默认按此计划执行）\n");
        for (int g = 0; g < groups.size(); g++) {
            List<WorkflowStep> group = groups.get(g);
            int stage = g + 1;
            if (group.size() == 1) {
                sb.append("阶段 ").append(stage).append("（单步）：")
                        .append(describeStep(group.get(0), available)).append("\n");
            } else {
                sb.append("阶段 ").append(stage)
                        .append("（并行，同一轮同时发起多个 subagent 调用）：\n");
                for (WorkflowStep s : group) {
                    sb.append("  - ").append(describeStep(s, available)).append("\n");
                }
            }
        }
        sb.append(buildRules(groups.size()));
        return sb.toString();
    }

    /** 编排规则 + 审计要求（P1：让「是否按计划执行」可被机器校验） */
    private static String buildRules(int stageCount) {
        return """
                
                编排规则：
                1. 按阶段顺序执行；同一阶段内标注「并行」的步骤必须在同一轮同时发起 subagent 调用，等全部返回后再进入下一阶段。
                2. 派发子 Agent 时把「任务指令」连同必要上下文写入 subagent 调用参数，不要让子 Agent 猜测任务。
                3. 每个阶段结束后先校验产出：不符合预期就地修正或自己补做，再进入下一阶段。
                4. 所有阶段完成后，由你（主控）汇总各成员产出，交叉验证后给出最终答复。
                5. 工作流是默认路径而非死板约束：任务明显不适用时可自行裁剪步骤，但需在回复中说明调整原因。
                6. 缺少可用成员（未注册的子 Agent）的阶段由你自己直接完成该步骤的工作。
                7. 本场景工作流中重复出现的子 Agent 按计划次数调度，不受「同一子 Agent 最多 2 次」的通用上限约束。
                
                执行审计（必须遵守）：
                完成任务后，在回复的最后一行输出如下审计标记，供系统校验编排是否按计划执行：
                <orchestration-audit stages="%d" executed="阶段号:子Agent名,..." skipped="被跳过的阶段号" />
                示例：<orchestration-audit stages="%d" executed="1:planner,2:coder|reviewer" skipped="" />
                说明：executed 中同一阶段的多个并行成员用 | 分隔，阶段之间用 , 分隔；
                裁剪掉的阶段填入 skipped 并在正文说明原因。
                """.formatted(stageCount, stageCount);
    }

    private static String describeStep(WorkflowStep step, Set<String> available) {
        String mark = available.contains(step.subagent()) ? "" : "（未注册，由主控自己完成）";
        String instruction = notBlank(step.instruction()) ? step.instruction() : "完成本阶段任务";
        return "子 Agent **" + step.subagent() + "**" + mark + " —— " + instruction;
    }

    private static String displayName(ScenarioEntity scenario) {
        if (notBlank(scenario.getDisplayName())) {
            return notBlank(scenario.getIcon())
                    ? scenario.getIcon() + " " + scenario.getDisplayName()
                    : scenario.getDisplayName();
        }
        return scenario.getName();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
