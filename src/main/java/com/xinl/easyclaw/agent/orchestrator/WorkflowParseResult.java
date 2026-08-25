package com.xinl.easyclaw.agent.orchestrator;

import java.util.Collections;
import java.util.List;

/**
 * 工作流解析结果
 * <p>
 * 显式区分三种状态，取代旧版「解析失败 → 静默返回空列表」的行为：
 * <ul>
 *   <li>空工作流：{@code steps} 空且 {@code errors} 空（single 模式的正常情况）</li>
 *   <li>非法工作流：{@code errors} 非空 —— 由校验层抛出可读错误，不再让用户面对
 *       误导性的「subagent 不能为空」</li>
 *   <li>合法工作流：{@code steps} 非空且 {@code errors} 空</li>
 * </ul>
 * {@code warnings} 用于「可继续执行但语义可疑」的情况（如首步标记 parallel）。
 *
 * @param steps    解析出的合法步骤（不可变）
 * @param errors   阻断性错误（非空即拒绝保存）
 * @param warnings 非阻断告警（记录日志并可回传 UI）
 */
public record WorkflowParseResult(List<WorkflowStep> steps,
                                  List<String> errors,
                                  List<String> warnings) {

    public WorkflowParseResult(List<WorkflowStep> steps, List<String> errors, List<String> warnings) {
        this.steps = List.copyOf(steps == null ? Collections.emptyList() : steps);
        this.errors = List.copyOf(errors == null ? Collections.emptyList() : errors);
        this.warnings = List.copyOf(warnings == null ? Collections.emptyList() : warnings);
    }

    /** 空结果（工作流未配置，非错误） */
    public static WorkflowParseResult empty() {
        return new WorkflowParseResult(List.of(), List.of(), List.of());
    }

    /** 仅含阻断性错误的结果 */
    public static WorkflowParseResult failed(String... errors) {
        return new WorkflowParseResult(List.of(), List.of(errors), List.of());
    }

    /** 无阻断性错误 */
    public boolean ok() {
        return errors.isEmpty();
    }

    /** 有可执行步骤（且无错误） */
    public boolean hasSteps() {
        return ok() && !steps.isEmpty();
    }

    /** 拼接为单行错误消息（用于异常文案） */
    public String errorMessage() {
        return String.join("；", errors);
    }
}
