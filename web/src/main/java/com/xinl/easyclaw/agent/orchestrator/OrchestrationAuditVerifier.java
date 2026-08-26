package com.xinl.easyclaw.agent.orchestrator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编排执行审计校验器（P1）
 * <p>
 * team 模式下，编排的「控制流」由主 LLM 按提示词自行执行，系统侧无法保证其
 * 一定按计划走。本类通过解析主智能体回复末尾的审计标记
 * <pre>&lt;orchestration-audit stages="2" executed="1:planner,2:coder|reviewer" skipped="" /&gt;</pre>
 * 与实际计划比对，把「编排是否真的发生」从不可观测变为可观测。
 * <p>
 * 注意：这是**可观测性**手段而非强制手段 —— LLM 可能漏输出审计行（记为
 * {@code MISSING_AUDIT}）或谎报。要获得执行保证需把编排下沉为确定性执行器。
 */
public final class OrchestrationAuditVerifier {

    /** 审计标记正则（属性顺序无关，容忍单双引号与空白） */
    private static final Pattern AUDIT_TAG = Pattern.compile(
            "<orchestration-audit\\s+([^>]*?)/?>", Pattern.CASE_INSENSITIVE);

    private static final Pattern ATTR = Pattern.compile(
            "(\\w+)\\s*=\\s*[\"']([^\"']*)[\"']");

    private OrchestrationAuditVerifier() {
    }

    /**
     * 审计比对结果
     *
     * @param auditPresent  回复中是否存在审计标记
     * @param plannedStages 计划阶段数
     * @param executedStages 审计声明已执行的阶段号
     * @param skippedStages 审计声明被跳过的阶段号
     * @param mismatches    计划与审计的差异描述（空表示一致）
     */
    public record AuditResult(boolean auditPresent,
                              int plannedStages,
                              Set<Integer> executedStages,
                              Set<Integer> skippedStages,
                              List<String> mismatches) {

        public boolean consistent() {
            return auditPresent && mismatches.isEmpty();
        }

        public String summary() {
            if (!auditPresent) {
                return "缺少编排审计标记（无法确认是否按计划执行）";
            }
            return mismatches.isEmpty()
                    ? "编排按计划执行（" + executedStages.size() + "/" + plannedStages + " 阶段）"
                    : String.join("；", mismatches);
        }
    }

    /**
     * 校验主智能体回复是否与编排计划一致。
     *
     * @param workflowJson 场景的工作流 JSON
     * @param finalReply   主智能体最终回复文本
     * @return 计划为空（非 team 或无步骤）时返回 empty，表示无需审计
     */
    public static Optional<AuditResult> verify(String workflowJson, String finalReply) {
        WorkflowParseResult parsed = WorkflowParser.parse(workflowJson);
        if (!parsed.hasSteps()) {
            return Optional.empty();
        }
        int plannedStages = WorkflowParser.groupByStage(parsed.steps()).size();

        String audit = extractAuditAttributes(finalReply);
        if (audit == null) {
            return Optional.of(new AuditResult(false, plannedStages, Set.of(), Set.of(),
                    List.of("缺少 <orchestration-audit> 标记")));
        }

        Set<Integer> executed = parseStageNumbers(attribute(audit, "executed"));
        Set<Integer> skipped = parseStageNumbers(attribute(audit, "skipped"));

        List<String> mismatches = new ArrayList<>();
        for (int stage = 1; stage <= plannedStages; stage++) {
            if (!executed.contains(stage) && !skipped.contains(stage)) {
                mismatches.add("阶段 " + stage + " 既未执行也未声明跳过");
            }
        }
        if (!skipped.isEmpty()) {
            mismatches.add("已跳过阶段 " + skipped);
        }
        return Optional.of(new AuditResult(true, plannedStages, executed, skipped, mismatches));
    }

    /** 提取审计标记的属性串（取最后一个，回复末尾优先） */
    private static String extractAuditAttributes(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        Matcher m = AUDIT_TAG.matcher(reply);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    private static String attribute(String attributes, String name) {
        Matcher m = ATTR.matcher(attributes);
        while (m.find()) {
            if (name.equalsIgnoreCase(m.group(1))) {
                return m.group(2);
            }
        }
        return "";
    }

    /**
     * 从 {@code "1:planner,2:coder|reviewer"} 或 {@code "2,3"} 中提取阶段号。
     * 容忍无阶段号的写法（如 {@code "planner,coder"}）——此时无法定位阶段，返回空集，
     * 由调用方按「阶段未确认」处理。
     */
    private static Set<Integer> parseStageNumbers(String value) {
        Set<Integer> stages = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return stages;
        }
        for (String part : value.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int colon = token.indexOf(':');
            String number = colon >= 0 ? token.substring(0, colon).trim() : token;
            try {
                stages.add(Integer.parseInt(number));
            } catch (NumberFormatException ignored) {
                // 非阶段号写法（直接写子 Agent 名）：跳过，不污染结果
            }
        }
        return stages;
    }
}
