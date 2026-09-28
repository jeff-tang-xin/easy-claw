package com.xinl.easyclaw.base.orchestration;

/**
 * 单个 {@link OrchestrationPlan.PlanStep} 的执行结果。
 * <p>
 * <b>失败不抛异常</b>：与 {@link OrchestrationPlan} 的错误处理约定一致 ——
 * 编排器需要在某一步失败后继续决策（返工 / 跳过 / 中止），异常会打断这个决策过程。
 * 因此执行失败表达为 {@code ok=false} + {@code error}，由编排器决定后续走向。
 * <p>
 * <b>truncated 单列而非并入 error</b>：步数耗尽是「产出了内容但可能不完整」，
 * 与「彻底失败」语义不同 —— 前者的 {@code output} 仍有价值（可能含关键中间结论），
 * 编排器可据此决定是续派还是采信。参见知识库
 * {@code agentscope-subagent-steps-truncation}：框架不会主动报告截断。
 *
 * @param agentId   实际执行该步的智能体标识
 * @param ok        是否成功
 * @param output    执行产出（失败时可能为部分产出，非 null）
 * @param error     失败原因，成功时为 null
 * @param truncated 是否因步数耗尽被截断（此时 output 可能不完整）
 */
public record StepResult(String agentId,
                         boolean ok,
                         String output,
                         String error,
                         boolean truncated) {

    public StepResult {
        output = output == null ? "" : output;
    }

    public static StepResult success(String agentId, String output) {
        return new StepResult(agentId, true, output, null, false);
    }

    /** 成功但被截断：产出可用，但编排器应警惕其完整性 */
    public static StepResult truncated(String agentId, String output) {
        return new StepResult(agentId, true, output, null, true);
    }

    public static StepResult failure(String agentId, String error) {
        return new StepResult(agentId, false, "", error, false);
    }

    /** 失败但有部分产出（如执行到一半报错） */
    public static StepResult failure(String agentId, String partialOutput, String error) {
        return new StepResult(agentId, false, partialOutput, error, false);
    }
}
