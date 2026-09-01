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
 *       的执行计划。主智能体在此模式下是<b>常驻协调者</b>——只做分发与验收，
 *       决定「质量（合格否）」与「走向（下一阶段 / 返工 / 调整）」，不亲自执行具体任务</li>
 * </ul>
 * <b>编排单位是角色</b>：角色自带人格与模型，是完整的执行单元；同一阶段的 N 个角色
 * 应被同时激活为 N 路并发调用。子 Agent 只是承载角色运行的载体（同名声明即该角色的
 * 执行体），属于实现细节，不进场景配置。
 * <p>
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
     * <p>
     * 输出固定三段式，对应「场景决定你处在什么环境里、什么能做什么不能做、该怎么做」：
     * <ol>
     *   <li><b>环境</b>——场景描述</li>
     *   <li><b>能力边界</b>——绑定的 skill / 子 Agent / MCP（软边界，见 {@code capabilityBoundary}）</li>
     *   <li><b>方法论</b>——场景提示词 + 协作方式（single 单智能体 / team 多智能体协作）</li>
     * </ol>
     * 能力边界原先由 {@code WorkspaceAgentBuilder} 单独拼在场景块之外，模型看到的是
     * 两段不相干的话；并入这里后「环境-边界-方法」在同一标题下形成完整语义。
     *
     * @param scenario   激活的场景（可为 null）
     * @param subagents  当前 Agent 已注册的子 Agent 声明（用于成员校验）
     * @param boundary   场景能力边界文本，可为 null；由调用方按绑定关系渲染
     */
    public static String build(ScenarioEntity scenario, List<SubagentDeclaration> subagents,
                               String boundary) {
        if (scenario == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 🎬 当前场景：").append(displayName(scenario)).append("\n");
        if (notBlank(scenario.getDescription())) {
            sb.append("\n### 你所处的环境\n").append(scenario.getDescription().trim()).append("\n");
        }
        if (notBlank(boundary)) {
            sb.append("\n### 能力边界\n").append(boundary.trim()).append("\n");
        }
        sb.append("\n### 工作方法论（").append(methodologyLabel(scenario)).append("）\n");
        int beforeMethod = sb.length();
        if (notBlank(scenario.getSystemPrompt())) {
            sb.append(scenario.getSystemPrompt().trim()).append("\n");
        }
        if ("team".equals(scenario.getMode())) {
            String orchestration = buildTeamOrchestration(scenario, subagents);
            if (orchestration != null) {
                sb.append("\n").append(orchestration);
            }
        }
        if (sb.length() == beforeMethod) {
            // 方法论段无内容：留一个空标题反而是噪声，回退为「按通用工作方式执行」
            sb.append("本场景未定义专属方法论，按基座的任务闭环协议执行。\n");
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /** 方法论标签：把 mode 翻译成模型能理解的协作方式，而不是内部枚举值 */
    private static String methodologyLabel(ScenarioEntity scenario) {
        return "team".equals(scenario.getMode()) ? "多智能体协作" : "单智能体";
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
        sb.append("### 🤝 角色编排工作流（本场景的任务默认按此计划执行）\n");
        for (int g = 0; g < groups.size(); g++) {
            List<WorkflowStep> group = groups.get(g);
            int stage = g + 1;
            if (group.size() == 1) {
                sb.append("阶段 ").append(stage).append("（单步）：")
                        .append(describeStep(group.get(0), available)).append("\n");
            } else {
                sb.append("阶段 ").append(stage)
                        .append("（并行：以下 ").append(group.size())
                        .append(" 个角色必须在同一轮同时派发，全部返回后统一验收）：\n");
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
                
                你的定位——协调者（常驻，不亲自干活）：
                本场景下你不是执行者，而是全程常驻的**分发者 + 验收者**。你只决定两件事：
                「质量」（这一阶段的产出合格吗）和「走向」（进入下一阶段，还是打回返工）。
                具体活由各角色的执行体去干；你不要替他们写代码、写文档、做调研。
                
                编排规则：
                1. 逐阶段推进。每个阶段开始时，把该阶段的每个角色都用 agent_spawn 派发出去，
                   agent_id 用角色名——每个角色都有自己独立的人格与模型，是独立的执行单元。
                2. 同一阶段有多个角色时，必须在同一轮里同时发起全部调用（并发执行），
                   等这一批全部返回后再验收，不要串行地一个个等。
                3. 派发时把「任务指令」连同必要上下文（上游阶段的产出、约束、验收标准）写进调用参数，
                   不要让执行体自己猜任务。
                4. 每个阶段返回后你必须做验收，给出明确结论之一：
                   - 通过 → 进入下一阶段，并把本阶段产出作为上下文带下去；
                   - 返工 → 指出具体问题，带着修改要求重新派发同一角色（返工不算新阶段）；
                   - 调整走向 → 说明原因后裁剪/追加步骤。
                   同一阶段的返工累计 2 次仍不达标时，停下来向用户说明卡点，不要无限重试。
                5. 计划中的角色没有专属执行体时，仍要派发出去执行（在指令里写清该角色的职责与视角），
                   而不是你自己动手完成。
                6. 全部阶段通过后，由你汇总各角色产出、交叉验证一致性，再给出最终答复。
                7. 工作流是默认路径而非死板约束：任务明显不适用时可裁剪步骤，但需在回复中说明原因。
                8. 本场景工作流中重复出现的角色按计划次数调度，且返工重派不受「同一执行体最多 2 次」的通用上限约束。
                
                执行审计（必须遵守）：
                完成任务后，在回复的最后一行输出如下审计标记，供系统校验编排是否按计划执行：
                <orchestration-audit stages="%d" executed="阶段号:角色名,..." skipped="被跳过的阶段号" />
                示例：<orchestration-audit stages="%d" executed="1:planner,2:coder|reviewer" skipped="" />
                说明：executed 中同一阶段的多个并行角色用 | 分隔，阶段之间用 , 分隔；
                裁剪掉的阶段填入 skipped 并在正文说明原因。
                """.formatted(stageCount, stageCount);
    }

    private static String describeStep(WorkflowStep step, Set<String> available) {
        String mark = available.contains(step.role()) ? "" : "（无专属执行体，派发时把该角色的职责要求写进指令）";
        String instruction = notBlank(step.instruction()) ? step.instruction() : "完成本阶段任务";
        return "角色 **" + step.role() + "**" + mark + " —— " + instruction;
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
