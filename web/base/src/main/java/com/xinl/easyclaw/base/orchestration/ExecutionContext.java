package com.xinl.easyclaw.base.orchestration;

import com.xinl.easyclaw.base.profile.ScenarioProfile;

/**
 * 编排执行上下文 —— {@link AgentOrchestrator} 的<b>统一输入</b>。
 * <p>
 * <b>为什么用一个 record 而不是多个参数</b>：{@code plan(scenario, task)} 的两参签名
 * 不足以支撑真实执行 —— 执行体需要知道在哪个工作区跑（{@code workspaceId}）、
 * 属于哪个会话（{@code sessionId}，用于事件回流与转录）。后续做阶段间上下文传递、
 * 取消信号时还会继续加字段。收进 record 后，新增字段不必改动所有实现与调用点。
 * <p>
 * <b>只读</b>：编排器不应反向修改运行时状态，所有产出通过返回值
 * （{@link OrchestrationPlan} / {@link OrchestrationResult}）表达。
 * <p>
 * <b>cancelSignal 为什么是函数式接口而非布尔字段</b>：后者在构造时拍定值，
 * 无法反映「用户点停止」的渐进语义 —— 编排器可能在构造上下文数秒后才开始执行。
 * 函数式接口让调用方可以传入一个 {@code AtomicBoolean::get} 或类似惰性求值表达式，
 * 取消信号在编排过程中随时生效。
 *
 * @param workspaceId  当前工作区标识，不可为空
 * @param sessionId    当前会话标识，可为 null（如后台任务无会话）
 * @param scenario     激活场景，可为 null（无场景绑定，回退单智能体默认行为）
 * @param task         用户本次输入的任务原文
 * @param cancelSignal 取消信号，编排器在每个步骤派发前检查；默认 {@link CancelSignal#NEVER}
 */
public record ExecutionContext(String workspaceId,
                               String sessionId,
                               ScenarioProfile scenario,
                               String task,
                               CancelSignal cancelSignal) {

    public ExecutionContext {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("ExecutionContext.workspaceId 不能为空");
        }
        task = task == null ? "" : task;
        cancelSignal = cancelSignal == null ? CancelSignal.NEVER : cancelSignal;
    }

    /** 便捷构造：无会话绑定的场景（如配置校验时试算计划） */
    public static ExecutionContext of(String workspaceId, ScenarioProfile scenario, String task) {
        return new ExecutionContext(workspaceId, null, scenario, task, CancelSignal.NEVER);
    }

    /** 带取消信号的便捷构造 */
    public static ExecutionContext of(String workspaceId, String sessionId,
                                      ScenarioProfile scenario, String task,
                                      CancelSignal cancelSignal) {
        return new ExecutionContext(workspaceId, sessionId, scenario, task, cancelSignal);
    }

    /** 场景是否已配置 */
    public boolean hasScenario() {
        return scenario != null;
    }

    /** 场景 mode，未绑定场景时返回 null（由 OrchestrationModes 兜底为 single） */
    public String modeId() {
        return scenario == null ? null : scenario.getMode();
    }

    /** 当前步骤是否已被取消（编排层在步骤边界检查） */
    public boolean isCancelled() {
        return cancelSignal.cancelled();
    }
}
