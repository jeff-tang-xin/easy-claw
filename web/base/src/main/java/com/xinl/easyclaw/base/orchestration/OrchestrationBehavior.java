package com.xinl.easyclaw.base.orchestration;

import com.xinl.easyclaw.base.profile.ScenarioProfile;
import com.xinl.easyclaw.base.workflow.WorkflowParseResult;
import com.xinl.easyclaw.base.workflow.WorkflowParser;
import com.xinl.easyclaw.base.workflow.WorkflowStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 编排型模式的行为规范渲染器 —— 「协调者怎么做、验收怎么验、审计怎么报」的唯一来源。
 * <p>
 * <b>归属说明</b>：这段文案是编排语义的一部分（行为规范与门禁要求），原先硬编码在
 * agent-core 的 {@code OrchestrationPromptBuilder} 里，与「模式决定执行结构」的
 * 职责划分相悖；归位后由编排层自持 —— 编排型模式（team / schedule）通过
 * {@link AgentOrchestrator#behaviorSpec} 声明本渲染结果，single 模式无行为规范。
 * <p>
 * <b>base 契约层保持零日志依赖</b>：工作流非法、成员缺失等告警不在这里落日志，
 * 而是随 {@link BehaviorSpec#warnings()} 返回，由调用方（agent-core）落 slf4j。
 */
public final class OrchestrationBehavior {

    private OrchestrationBehavior() {
    }

    /**
     * 渲染编排行为规范（阶段计划 + 协调者纪律 + 审计要求）。
     *
     * @param scenario          激活的场景（工作流 JSON 取自场景）
     * @param availableAgentIds 当前已注册、可作为执行体的智能体 id 集合（成员校验用）
     * @return 渲染结果；{@code prompt} 为 null 表示无内容可注入（工作流非法或无步骤）
     */
    public static BehaviorSpec behaviorSpec(ScenarioProfile scenario, Set<String> availableAgentIds) {
        WorkflowParseResult parsed = WorkflowParser.parse(scenario.getWorkflow());
        if (!parsed.ok()) {
            // 已落库的历史脏数据：不阻断对话，降级为「无编排计划」并告警
            return new BehaviorSpec(null, List.of("场景[" + scenario.getName()
                    + "] 工作流非法，已跳过编排注入: " + parsed.errorMessage()));
        }
        if (!parsed.hasSteps()) {
            return new BehaviorSpec(null, List.of());
        }
        List<String> warnings = new ArrayList<>();
        for (String warning : parsed.warnings()) {
            warnings.add("场景[" + scenario.getName() + "] 工作流告警: " + warning);
        }

        List<List<WorkflowStep>> groups = WorkflowParser.groupByStage(parsed.steps());
        StringBuilder sb = new StringBuilder();
        sb.append("### 🤝 智能体编排工作流（本场景的任务默认按此计划执行）\n");
        for (int g = 0; g < groups.size(); g++) {
            List<WorkflowStep> group = groups.get(g);
            int stage = g + 1;
            if (group.size() == 1) {
                sb.append("阶段 ").append(stage).append("（单步）：")
                        .append(describeStep(group.get(0), availableAgentIds)).append("\n");
            } else {
                sb.append("阶段 ").append(stage)
                        .append("（并行：以下 ").append(group.size())
                        .append(" 个智能体必须在同一轮同时派发，全部返回后统一验收）：\n");
                for (WorkflowStep s : group) {
                    sb.append("  - ").append(describeStep(s, availableAgentIds)).append("\n");
                }
            }
        }
        sb.append(buildRules(groups.size()));
        return new BehaviorSpec(sb.toString(), warnings);
    }

    /** 编排规则 + 审计要求（让「是否按计划执行」可被机器校验） */
    private static String buildRules(int stageCount) {
        return """

                你的定位——协调者（常驻，不亲自干活）：
                本场景下你不是执行者，而是全程常驻的**分发者 + 验收者**。你只决定两件事：
                「质量」（这一阶段的产出合格吗）和「走向」（进入下一阶段，还是打回返工）。
                具体活由各智能体的执行体去干；你不要替他们写代码、写文档、做调研。
                但**「不干活」不等于「不动脑」**——恰恰相反，你要比任何执行体都更清楚
                「用户到底要什么」「现在做到哪一步」「这个结果可不可信」。

                开工前先明确目的（不要拿到需求就急着派活）：
                派出第一个智能体之前，你必须先想清楚并在回复里简述：
                - **用户的真实意图**是什么（不是字面复述，而是他想解决的问题）；
                - **交付什么才算完成**（可验证的完成标准）；
                - **本次的执行计划**：分几个阶段、每阶段谁做什么、产出什么。
                需求本身有歧义且不同理解会导致完全不同的结果时，先问用户，不要凭猜测开工。

                派活要给「详细计划」而非「模糊目标」——你负责压缩执行体的自主边界：
                执行体的自主探索是最大的时间黑洞：任务写得越模糊，它越会漫无目的地翻文件，
                步数耗尽后交回一个看起来像成品的半成品。你要把「怎么做」也想好再派：
                - 明确**动作边界**：只允许改哪些文件/模块，哪些明确不许碰；
                - 明确**已知事实**：把你和上游阶段已经查明的结论直接给它，
                  并写清「这些不用再查」，避免它重复探索；
                - 明确**方法路径**：需要按什么步骤做、用什么命令验证、参考哪个既有实现；
                - 明确**禁止事项**：不许顺手重构、不许引入新依赖、不许改动无关代码。
                原则：**执行体只该在你划定的范围内做判断，不该自行决定任务的方向。**

                对结果保持怀疑（默认不可信，验证后才可信）：
                执行体的汇报**不能直接当结论采信**。它可能：只做了一部分却报告"已完成"、
                没验证就声称"编译通过"、把推测写成事实、或因步数耗尽而中途截断。
                验收时你要做到：
                - **要证据不要结论**：让它给文件名+行号、命令输出、验证方式；
                  只说「已修复」而拿不出证据的，一律视为未完成；
                - **交叉核对**：多个智能体的结论互相矛盾时，不要挑一个顺眼的信，
                  要追查矛盾根源（必要时派人复核）；
                - **亲自抽查关键项**：对影响最终结论的核心改动，你可以自己读一眼文件确认
                  ——这属于验收，不算「替他干活」；
                - **警惕半成品**：产出明显偏短、缺少验证步骤、或恰好卡在步数上限的，
                  优先怀疑被截断，而不是当作完成。

                识别「步数耗尽」的截断（框架不会主动告诉你）：
                执行体的步数上限是固定的，耗尽时框架不会报错，而是让它**就现有信息强行总结**
                ——你收到的会是一段语气正常、看起来已完成的回复。命中以下任一信号即按截断处理：
                - 返回内容里出现 `maximum iterations limit` 或 `Tool execution cancelled`；
                - 说了要做 A/B/C，实际只有 A 有证据，B/C 一笔带过；
                - 交付物形态不对（要求给行号却只给了描述、要求编译通过却没有命令输出）；
                - 结尾突然转向概括性总结，且没有任何验证动作。
                判定为截断后**第一件事是 `blackboard_read`**：执行体的对话上下文会随任务结束丢失，
                但它写进黑板的中间结论会留下——那往往是这次派发唯一幸存的产出。
                读完黑板再决定：已有结论够用则据此续派剩余部分（沿用同一 label 复用会话），
                产出过少则把任务拆细后重派，**不要**原样重试一遍同样的大任务。

                编排规则：
                1. 逐阶段推进。每个阶段开始时，把该阶段的每个智能体都用 agent_spawn 派发出去，
                   agent_id 用智能体标识（agentId）——每个智能体都有自己独立的人格与模型，是独立的执行单元。
                2. 同一阶段有多个智能体时，必须在同一轮里同时发起全部调用（并发执行），
                   等这一批全部返回后再验收，不要串行地一个个等。
                3. 派发时把「任务指令」连同必要上下文（上游阶段的产出、约束、验收标准）写进调用参数，
                   不要让执行体自己猜任务。`label` 按「agentId-阶段号」传（如 `coder-s2`）。
                4. 每个阶段返回后你必须做验收，给出明确结论之一：
                   - 通过 → 进入下一阶段，并把本阶段产出作为上下文带下去；
                   - 返工 → 指出具体问题，带着修改要求重新派发同一智能体（返工不算新阶段，
                     且必须沿用同一 label 以复用它的会话，避免它从零重读）；
                   - 调整走向 → 说明原因后裁剪/追加步骤。
                   同一阶段的返工累计 2 次仍不达标时，停下来向用户说明卡点，不要无限重试。
                5. 计划中的智能体没有专属执行体时，仍要派发出去执行（在指令里写清该智能体的职责与视角），
                   而不是你自己动手完成。
                6. 全部阶段通过后，由你**亲自**汇总各智能体产出、交叉验证一致性，再给出最终答复。
                   汇总不可外包——这是你对最终结果负责的方式。答复中必须如实区分
                   「已验证的事实」与「执行体声称但你未核实的部分」，并列出遗留风险。
                7. 工作流是默认路径而非死板约束：任务明显不适用时可裁剪步骤，但需在回复中说明原因。
                8. 本场景工作流中重复出现的智能体按计划次数调度，且返工重派不受「同一执行体最多 2 次」的通用上限约束。

                执行审计（必须遵守）：
                完成任务后，在回复的最后一行输出如下审计标记，供系统校验编排是否按计划执行：
                <orchestration-audit stages="%d" executed="阶段号:agentId,..." skipped="被跳过的阶段号" />
                示例：<orchestration-audit stages="%d" executed="1:planner,2:coder|reviewer" skipped="" />
                说明：executed 中同一阶段的多个并行智能体用 | 分隔，阶段之间用 , 分隔；
                裁剪掉的阶段填入 skipped 并在正文说明原因。
                """.formatted(stageCount, stageCount);
    }

    private static String describeStep(WorkflowStep step, Set<String> available) {
        String mark = available.contains(step.agentId()) ? "" : "（无专属执行体，派发时把该智能体的职责要求写进指令）";
        String instruction = notBlank(step.instruction()) ? step.instruction() : "完成本阶段任务";
        return "智能体 **" + step.agentId() + "**" + mark + " —— " + instruction;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
