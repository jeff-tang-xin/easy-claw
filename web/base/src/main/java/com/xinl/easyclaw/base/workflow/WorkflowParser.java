package com.xinl.easyclaw.base.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工作流 JSON 解析与校验
 * <p>
 * 从 {@link OrchestrationPromptBuilder} 拆出，专职负责「JSON ↔ 步骤列表」的
 * 双向转换与 schema 校验，使提示词构建只关心渲染。
 * <p>
 * 相比旧版 {@code parseWorkflow} 的关键差异（P0 修复）：
 * <ul>
 *   <li>语法错误不再被静默吞掉，而是作为阻断性错误返回</li>
 *   <li>未知字段（如把 parallel 拼成 paralel）会报错，避免语义被悄悄改变</li>
 *   <li>agentId 缺失的步骤不再静默丢弃，而是定位到具体下标报错</li>
 *   <li>步骤数上限，防止超长工作流撑爆 system prompt</li>
 * </ul>
 */
public final class WorkflowParser {

    /** 单个场景允许的最大步骤数（防止提示词膨胀） */
    public static final int MAX_STEPS = 20;

    /** 单条指令允许的最大长度（防止提示词注入面无上限扩张） */
    public static final int MAX_INSTRUCTION_LENGTH = 500;

    /** 工作流 JSON 原文长度上限（防止超大输入在解析阶段即耗尽内存） */
    public static final int MAX_JSON_LENGTH = 64 * 1024;

    /**
     * 智能体标识合法字符集：字母、数字、下划线、连字符、点。
     * <p>
     * 名字会被直接拼进 system prompt，若允许换行或尖括号，攻击者可在场景配置里
     * 伪造审计标记（{@code <orchestration-audit .../>}）或注入额外指令段，
     * 因此在入口就限制为标识符字符。
     */
    private static final Pattern AGENT_ID_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /** 用于剥离 instruction 中伪造的审计标记 */
    private static final Pattern AUDIT_TAG_IN_TEXT = Pattern.compile(
            "</?orchestration-audit[^>]*>", Pattern.CASE_INSENSITIVE);

    /**
     * 步骤对象允许出现的字段，其余一律视为拼写错误。
     * <p>
     * {@code agentId} 是当前字段，{@code role} 与 {@code subagent} 是<b>历史字段</b>：
     * 编排单位历经「子 Agent（subagent）→ 角色（role）→ 智能体（agentId）」两次正名，
     * 运行时始终把该值当 SPI agentId 使用。这里仍接受两个旧字段，是为了让存量场景
     * JSON 能被读起来并自动迁移（见 {@code parseStep}），但序列化时只写 {@code agentId}。
     */
    private static final Set<String> ALLOWED_STEP_FIELDS =
            Set.of("agentId", "role", "instruction", "parallel", "subagent");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowParser() {
    }

    /**
     * 严格解析工作流 JSON。
     * <p>
     * 空输入返回 {@link WorkflowParseResult#empty()}（不视为错误，由调用方按 mode 决定是否必填）。
     *
     * @param workflowJson 形如 {@code {"steps":[{"agentId":"planner","instruction":"...","parallel":false}]}}
     */
    public static WorkflowParseResult parse(String workflowJson) {
        if (workflowJson == null || workflowJson.isBlank()) {
            return WorkflowParseResult.empty();
        }
        if (workflowJson.length() > MAX_JSON_LENGTH) {
            return WorkflowParseResult.failed(
                    "工作流 JSON 长度 " + workflowJson.length() + " 超过上限 " + MAX_JSON_LENGTH);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(workflowJson);
        } catch (JsonProcessingException e) {
            return WorkflowParseResult.failed("工作流 JSON 语法错误: " + e.getOriginalMessage());
        }

        JsonNode stepsNode = root.path("steps");
        if (stepsNode.isMissingNode() || stepsNode.isNull()) {
            return WorkflowParseResult.failed("工作流缺少 steps 数组");
        }
        if (!stepsNode.isArray()) {
            return WorkflowParseResult.failed("工作流 steps 必须是数组");
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<WorkflowStep> steps = new ArrayList<>();

        if (stepsNode.size() > MAX_STEPS) {
            errors.add("工作流步骤数 " + stepsNode.size() + " 超过上限 " + MAX_STEPS);
        }

        for (int i = 0; i < stepsNode.size(); i++) {
            parseStep(stepsNode.get(i), i, steps, errors, warnings);
        }

        if (!steps.isEmpty() && steps.get(0).parallel()) {
            warnings.add("步骤[0] 标记 parallel=true 但无前序步骤可并行，将按独立阶段执行");
        }
        return new WorkflowParseResult(steps, errors, warnings);
    }

    /** 解析单个步骤节点，错误累积到 errors（不抛异常，便于一次性报出全部问题） */
    private static void parseStep(JsonNode node, int index, List<WorkflowStep> steps,
                                  List<String> errors, List<String> warnings) {
        String prefix = "步骤[" + index + "] ";
        if (!node.isObject()) {
            errors.add(prefix + "必须是对象");
            return;
        }

        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!ALLOWED_STEP_FIELDS.contains(field)) {
                errors.add(prefix + "存在未知字段 \"" + field + "\"（允许: " + ALLOWED_STEP_FIELDS + "）");
            }
        }

        JsonNode agentIdNode = node.path("agentId");
        if (!agentIdNode.isMissingNode() && !agentIdNode.isTextual() && !agentIdNode.isNull()) {
            errors.add(prefix + "agentId 必须是字符串");
            return;
        }
        String agentId = agentIdNode.asText("").trim();
        if (agentId.isEmpty()) {
            // 存量数据迁移（两级历史字段）：字段历经 subagent → role → agentId 两次正名，
            // 三者命名空间本就重合（code-expert / researcher 等既是 agentId 也是历史角色名），
            // 因此直接顶替，避免历史场景升级后工作流整段失效。
            String legacyRole = node.path("role").asText("").trim();
            String legacySubagent = node.path("subagent").asText("").trim();
            if (!legacyRole.isEmpty()) {
                warnings.add(prefix + "使用了历史字段 role，已按智能体 \"" + legacyRole
                        + "\" 解析（编排单位已正名为 agentId，请重新保存该场景）");
                agentId = legacyRole;
            } else if (!legacySubagent.isEmpty()) {
                warnings.add(prefix + "使用了历史字段 subagent，已按智能体 \"" + legacySubagent
                        + "\" 解析（编排单位已正名为 agentId，请重新保存该场景）");
                agentId = legacySubagent;
            }
        }
        if (agentId.isEmpty()) {
            errors.add(prefix + "agentId 不能为空");
            return;
        }
        if (!AGENT_ID_NAME.matcher(agentId).matches()) {
            errors.add(prefix + "agentId \"" + agentId
                    + "\" 含非法字符（仅允许字母/数字/下划线/连字符/点，最长 64）");
            return;
        }

        JsonNode parallelNode = node.path("parallel");
        if (!parallelNode.isMissingNode() && !parallelNode.isNull() && !parallelNode.isBoolean()) {
            errors.add(prefix + "parallel 必须是布尔值（true/false）");
            return;
        }

        String instruction = node.path("instruction").asText("").trim();
        if (instruction.length() > MAX_INSTRUCTION_LENGTH) {
            warnings.add(prefix + "instruction 超过 " + MAX_INSTRUCTION_LENGTH + " 字符，已截断");
            instruction = instruction.substring(0, MAX_INSTRUCTION_LENGTH);
        }
        // instruction 会进入 system prompt：剥离伪造的审计标记与结束标签，
        // 防止场景配置方通过指令文本谎报编排执行情况
        String sanitized = AUDIT_TAG_IN_TEXT.matcher(instruction).replaceAll("");
        if (!sanitized.equals(instruction)) {
            warnings.add(prefix + "instruction 含 <orchestration-audit> 标记，已移除（审计仅由运行时生成）");
            instruction = sanitized.trim();
        }

        steps.add(new WorkflowStep(agentId, instruction, parallelNode.asBoolean(false)));
    }

    /**
     * 序列化工作流为 JSON（改用 Jackson 构建，取代旧版手写字符串拼接）
     * <p>
     * 只写当前字段 {@code agentId}，不再写历史的 {@code role}/{@code subagent}——存量数据在
     * {@link #parse} 阶段已迁移为 agentId，重新保存一次即可完成落库形态的统一。
     *
     * @return 步骤为空时返回 null（与实体「未配置工作流」的表示保持一致）
     */
    public static String write(List<WorkflowStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        var root = MAPPER.createObjectNode();
        var array = root.putArray("steps");
        for (WorkflowStep step : steps) {
            array.addObject()
                    .put("agentId", step.agentId())
                    .put("instruction", step.instruction())
                    .put("parallel", step.parallel());
        }
        return root.toString();
    }

    /**
     * 把步骤按并行组归组：{@code parallel=true} 的步骤并入上一组，否则新开一组。
     * <p>
     * P0 修复：移除旧版 {@code !groups.getLast().isEmpty()} 永真死条件；首个步骤
     * 标记 parallel 时归入独立组（并由 {@link #parse} 产生告警），不再静默吞掉语义。
     *
     * @return 不可变的分组列表，每组内部为可并行执行的步骤
     */
    public static List<List<WorkflowStep>> groupByStage(List<WorkflowStep> steps) {
        List<List<WorkflowStep>> groups = new ArrayList<>();
        if (steps == null) {
            return groups;
        }
        for (WorkflowStep step : steps) {
            boolean joinPrevious = step.parallel() && !groups.isEmpty();
            if (joinPrevious) {
                groups.get(groups.size() - 1).add(step);
            } else {
                List<WorkflowStep> group = new ArrayList<>();
                group.add(step);
                groups.add(group);
            }
        }
        return groups;
    }
}
