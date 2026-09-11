package com.xinl.easyclaw.base.workflow;

/**
 * 编排工作流的单个步骤
 * <p>
 * 对应场景 {@code workflow} JSON 中 {@code steps[]} 的一项：
 * <pre>{"agentId":"code-expert","instruction":"实现登录接口","parallel":false}</pre>
 * <p>
 * <b>编排的单位是「智能体」（{@code agentId}）。</b>{@code agentId} 指向 SPI 注册表中的
 * 某个智能体，它自带人格与工具策略，是完整的执行单元；子 Agent 只是 harness
 * 层承载它运行的载体，属于实现细节，不该出现在场景配置里。
 * <p>
 * 历史上该字段曾叫 {@code subagent}、{@code role}，运行时一直把该值当 agentId 使用；
 * 方案 C 角色系统下线后统一正名为 {@code agentId}。解析层仍兼容读取旧字段
 * {@code role}/{@code subagent}（见 {@code WorkflowParser}），序列化只写新字段。
 * <p>
 * <b>执行顺序</b>由 {@code steps[]} 的数组下标决定，本记录不携带序号字段——序号一旦
 * 落库就和数组顺序形成两个真相源，重排时容易只改一处。
 * <p>
 * {@code parallel} 的语义是「与上一步骤归入同一并行组」，而非「本步骤自身可并行」。
 * 这是历史隐式编码，容易误用；首个步骤标记 parallel 时解析层会产生告警。
 *
 * @param agentId     执行该步骤的智能体标识（必填，非空），对应 SPI 注册表 agentId
 * @param instruction 该步骤的任务指令（可为空串，渲染时回退为默认文案）
 * @param parallel    是否与上一步骤并行
 */
public record WorkflowStep(String agentId, String instruction, boolean parallel) {

    public WorkflowStep {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("WorkflowStep.agentId 不能为空");
        }
        agentId = agentId.trim();
        instruction = instruction == null ? "" : instruction.trim();
    }
}
