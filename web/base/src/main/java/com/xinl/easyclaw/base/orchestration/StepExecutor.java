package com.xinl.easyclaw.base.orchestration;

import java.util.concurrent.CompletableFuture;

/**
 * 步骤执行器 —— <b>编排层与运行时之间的解耦边界</b>。
 * <p>
 * <b>为什么必须有这个接口</b>：mode 模块（{@code mode-single} / {@code mode-team} /
 * {@code mode-schedule}）只依赖 {@code base}，编译期够不到 {@code HarnessAgent}
 * 与运行时配置（model 密钥、toolkit、工作区路径都在 api 层）。若让 mode 反向依赖 api
 * 会构成循环依赖，Maven 直接构建失败。
 * <p>
 * 因此职责这样切分：
 * <ul>
 *   <li><b>mode 决定「编排语义」</b>——谁先谁后、哪些并行、产出是否合格、要不要返工；</li>
 *   <li><b>本接口的实现方（api 层）决定「怎么真的跑起来」</b>——按 agentId 查智能体声明、
 *       组装运行时、执行、回流事件。</li>
 * </ul>
 * 即：<b>编排归 mode，执行动作归实现方</b>。mode 调用本接口完成每一步，
 * 但不知道也不需要知道背后是 HarnessAgent 还是别的运行时。
 *
 * <h2>为什么返回 CompletableFuture 而不是直接返回 StepResult</h2>
 * 真实的执行体是<b>流式且可中断</b>的：{@code AgentService.streamChat} 通过
 * {@code onEvent / onError / onFinish} 三个回调持续向前端推送增量（reasoning、text、
 * tool 调用等），中途还可能因工具需要用户确认而<b>挂起整个回合</b>，
 * 等用户点了「允许」再由 {@code resumeChat} 接续。
 * <p>
 * 若本接口声明为同步的 {@code StepResult execute(...)}，实现方就只能把这套流式回调
 * 阻塞成一个返回值，代价是<b>两个真实业务能力当场报废</b>：
 * <ol>
 *   <li><b>流式输出消失</b> —— 前端要等整步跑完才一次性收到全文，
 *       而逐字输出是本产品的核心交互；</li>
 *   <li><b>工具确认死锁</b> —— 挂起等待用户点击时，执行线程被阻塞在这里，
 *       而恢复动作依赖同一套会话状态，构成自锁。</li>
 * </ol>
 * 返回 {@code CompletableFuture} 后，实现方可以在 {@code onFinish} 里
 * {@code complete}、在 {@code onError} 里 {@code completeExceptionally}，
 * 流式推送与确认挂起都原样保留，编排层只关心「这步什么时候算完、结果如何」。
 *
 * <h2>实现方约定</h2>
 * <ol>
 *   <li><b>不得让 future 异常完成来表达业务失败</b> —— 失败以
 *       {@link StepResult#failure} 正常完成，否则会打断编排器的返工决策。
 *       {@code completeExceptionally} 只留给真正的意外（如运行时组装失败）；</li>
 *   <li><b>必须保证 future 最终完成</b> —— 任何分支（含提前 return、异常、用户取消）
 *       都要落到 complete，否则整条编排链会永久挂起，表现为「对话卡住无响应」；</li>
 *   <li>需自行处理事件回流（增量推送、工具确认），编排器不负责；</li>
 *   <li>识别步数耗尽并以 {@link StepResult#truncated} 标记，
 *       不要伪装成正常成功 —— 半成品被当成品是最危险的失败模式。</li>
 * </ol>
 */
@FunctionalInterface
public interface StepExecutor {

    /**
     * 异步执行计划中的一步。
     * <p>
     * 实现方应立即返回 future 而不阻塞调用线程；执行过程中的增量输出通过
     * 实现方自己持有的事件通道推送，不经过本接口。
     *
     * @param step 要执行的步骤（含 agentId 与 instruction）
     * @param ctx  本次编排的执行上下文
     * @return 该步骤的执行结果 future；业务失败以 {@code ok=false} 正常完成，不抛异常
     */
    CompletableFuture<StepResult> execute(OrchestrationPlan.PlanStep step, ExecutionContext ctx);
}
