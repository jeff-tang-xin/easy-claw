package com.xinl.easyclaw.agent.orchestrator;

/**
 * 编排工作流的单个步骤
 * <p>
 * 对应场景 {@code workflow} JSON 中 {@code steps[]} 的一项：
 * <pre>{"subagent":"planner","instruction":"拆解任务","parallel":false}</pre>
 * <p>
 * {@code parallel} 的语义是「与上一步骤归入同一并行组」，而非「本步骤自身可并行」。
 * 这是历史隐式编码，容易误用；首个步骤标记 parallel 时解析层会产生告警。
 *
 * @param subagent    子 Agent 名称（必填，非空）
 * @param instruction 该步骤的任务指令（可为空串，渲染时回退为默认文案）
 * @param parallel    是否与上一步骤并行
 */
public record WorkflowStep(String subagent, String instruction, boolean parallel) {

    public WorkflowStep {
        if (subagent == null || subagent.isBlank()) {
            throw new IllegalArgumentException("WorkflowStep.subagent 不能为空");
        }
        subagent = subagent.trim();
        instruction = instruction == null ? "" : instruction.trim();
    }
}
