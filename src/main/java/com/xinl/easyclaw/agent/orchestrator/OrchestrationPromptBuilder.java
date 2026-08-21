package com.xinl.easyclaw.agent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
 * 工作流 JSON 格式：
 * <pre>{"steps":[{"subagent":"planner","instruction":"拆解任务","parallel":false}]}</pre>
 * parallel=true 的步骤与上一步同组并行执行。
 */
public final class OrchestrationPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationPromptBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OrchestrationPromptBuilder() {
    }

    /** 工作流步骤（解析后的中间结构） */
    public record WorkflowStep(String subagent, String instruction, boolean parallel) {
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
     * 连续 parallel=true 的步骤与前一步骤归入同一并行组。
     */
    private static String buildTeamOrchestration(ScenarioEntity scenario,
                                                 List<SubagentDeclaration> subagents) {
        List<WorkflowStep> steps = parseWorkflow(scenario.getWorkflow());
        if (steps.isEmpty()) {
            return null;
        }
        Set<String> available = new LinkedHashSet<>();
        for (SubagentDeclaration d : subagents) {
            available.add(d.getName());
        }

        // 归组：parallel=true 与上一步同组，否则新开一组
        List<List<WorkflowStep>> groups = new ArrayList<>();
        for (WorkflowStep step : steps) {
            if (!groups.isEmpty() && step.parallel() && !groups.getLast().isEmpty()) {
                groups.getLast().add(step);
            } else {
                groups.add(new ArrayList<>(List.of(step)));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("### 🤝 多智能体编排工作流（本场景的任务默认按此计划执行）\n");
        for (int g = 0; g < groups.size(); g++) {
            List<WorkflowStep> group = groups.get(g);
            if (group.size() == 1) {
                WorkflowStep s = group.get(0);
                sb.append("阶段 ").append(g + 1).append("（串行）：")
                        .append(describeStep(s, available)).append("\n");
            } else {
                sb.append("阶段 ").append(g + 1).append("（并行，同时发起多个 subagent 调用）：\n");
                for (WorkflowStep s : group) {
                    sb.append("  - ").append(describeStep(s, available)).append("\n");
                }
            }
        }
        sb.append("""
                
                编排规则：
                1. 按阶段顺序执行；同一阶段内标注「并行」的步骤必须在同一轮同时发起 subagent 调用，等全部返回后再进入下一阶段。
                2. 派发子 Agent 时把「任务指令」连同必要上下文写入 subagent 调用参数，不要让子 Agent 猜测任务。
                3. 每个阶段结束后先校验产出：不符合预期就地修正或自己补做，再进入下一阶段。
                4. 所有阶段完成后，由你（主控）汇总各成员产出，交叉验证后给出最终答复。
                5. 工作流是默认路径而非死板约束：任务明显不适用时可自行裁剪步骤，但需在回复中说明调整原因。
                6. 缺少可用成员（未注册的子 Agent）的阶段由你自己直接完成该步骤的工作。
                """);
        return sb.toString();
    }

    private static String describeStep(WorkflowStep step, Set<String> available) {
        String name = step.subagent() == null ? "?" : step.subagent();
        String mark = available.contains(name) ? "" : "（未注册，由主控自己完成）";
        String instruction = notBlank(step.instruction()) ? step.instruction().trim() : "完成本阶段任务";
        return "子 Agent **" + name + "**" + mark + " —— " + instruction;
    }

    /**
     * 解析工作流 JSON（容错：格式非法时返回空列表并告警）
     */
    public static List<WorkflowStep> parseWorkflow(String workflowJson) {
        List<WorkflowStep> steps = new ArrayList<>();
        if (workflowJson == null || workflowJson.isBlank()) {
            return steps;
        }
        try {
            JsonNode root = MAPPER.readTree(workflowJson);
            JsonNode arr = root.path("steps");
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    String subagent = node.path("subagent").asText(null);
                    String instruction = node.path("instruction").asText("");
                    boolean parallel = node.path("parallel").asBoolean(false);
                    if (subagent != null && !subagent.isBlank()) {
                        steps.add(new WorkflowStep(subagent.trim(), instruction, parallel));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析场景工作流 JSON 失败（忽略工作流）: {}", e.getMessage());
        }
        return steps;
    }

    /** 序列化工作流（null 安全） */
    public static String writeWorkflow(List<WorkflowStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{\"steps\":[");
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep s = steps.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"subagent\":").append(quote(s.subagent()))
                    .append(",\"instruction\":").append(quote(s.instruction()))
                    .append(",\"parallel\":").append(s.parallel()).append("}");
        }
        return sb.append("]}").toString();
    }

    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value == null ? "" : value);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private static String displayName(ScenarioEntity scenario) {
        if (notBlank(scenario.getDisplayName())) {
            return scenario.getIcon() != null && !scenario.getIcon().isBlank()
                    ? scenario.getIcon() + " " + scenario.getDisplayName()
                    : scenario.getDisplayName();
        }
        return scenario.getName();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
