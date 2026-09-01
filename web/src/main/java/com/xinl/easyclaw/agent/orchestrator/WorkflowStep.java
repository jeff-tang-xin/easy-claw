package com.xinl.easyclaw.agent.orchestrator;

/**
 * 编排工作流的单个步骤
 * <p>
 * 对应场景 {@code workflow} JSON 中 {@code steps[]} 的一项：
 * <pre>{"role":"code-expert","instruction":"实现登录接口","parallel":false}</pre>
 * <p>
 * <b>编排的单位是「角色」，不是子 Agent。</b>角色自带人格（role/goal/backstory）与
 * 模型配置（model/baseUrl/apiKey），本身就是完整的执行单元；子 Agent 只是 harness
 * 层承载它运行的载体，属于实现细节，不该出现在场景配置里。
 * <p>
 * <b>执行顺序</b>由 {@code steps[]} 的数组下标决定，本记录不携带序号字段——序号一旦
 * 落库就和数组顺序形成两个真相源，重排时容易只改一处。
 * <p>
 * {@code parallel} 的语义是「与上一步骤归入同一并行组」，而非「本步骤自身可并行」。
 * 这是历史隐式编码，容易误用；首个步骤标记 parallel 时解析层会产生告警。
 *
 * @param role        执行该步骤的角色名（必填，非空）
 * @param instruction 该步骤的任务指令（可为空串，渲染时回退为默认文案）
 * @param parallel    是否与上一步骤并行
 */
public record WorkflowStep(String role, String instruction, boolean parallel) {

    public WorkflowStep {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("WorkflowStep.role 不能为空");
        }
        role = role.trim();
        instruction = instruction == null ? "" : instruction.trim();
    }
}
