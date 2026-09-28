package com.xinl.easyclaw.base.orchestration;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排执行的整体结果 —— {@link AgentOrchestrator#execute} 的输出。
 * <p>
 * 与 {@link OrchestrationPlan} 对称：plan 是「打算怎么跑」，本类是「实际跑成什么样」。
 * 保留 {@code stepResults} 全量明细而非只给最终产出，因为编排型模式（team/schedule）
 * 的价值恰恰在过程 —— 哪一步返工过、哪一步被截断，都是调用方需要如实呈现给用户的。
 *
 * @param modeId      产出该结果的模式标识
 * @param ok          整体是否成功
 * @param stepResults 各步骤执行明细，按实际执行顺序（含返工产生的重复步骤）
 * @param finalOutput 最终交付产出
 * @param errors      阻断性错误
 */
public record OrchestrationResult(String modeId,
                                  boolean ok,
                                  List<StepResult> stepResults,
                                  String finalOutput,
                                  List<String> errors) {

    public OrchestrationResult {
        stepResults = stepResults == null ? List.of() : List.copyOf(stepResults);
        errors = errors == null ? List.of() : List.copyOf(errors);
        finalOutput = finalOutput == null ? "" : finalOutput;
    }

    /** 计划不可执行（如 workflow 配置非法）时的结果 */
    public static OrchestrationResult rejected(OrchestrationPlan plan) {
        return new OrchestrationResult(plan.modeId(), false, List.of(), "", plan.errors());
    }

    /** 单步模式的便捷构造 */
    public static OrchestrationResult of(String modeId, StepResult step) {
        List<StepResult> all = List.of(step);
        return new OrchestrationResult(modeId, step.ok(), all, step.output(),
                step.ok() ? List.of() : List.of(step.error()));
    }

    /** 是否有任一步骤被截断（调用方应据此提示用户产出可能不完整） */
    public boolean hasTruncation() {
        return stepResults.stream().anyMatch(StepResult::truncated);
    }

    /** 收集失败步骤，便于调用方定位问题 */
    public List<StepResult> failures() {
        List<StepResult> out = new ArrayList<>();
        for (StepResult r : stepResults) {
            if (!r.ok()) {
                out.add(r);
            }
        }
        return out;
    }
}
