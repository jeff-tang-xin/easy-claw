package com.xinl.easyclaw.agent.orchestrator;

import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.agent.domain.UserAttachment;
import com.xinl.easyclaw.base.orchestration.ExecutionContext;
import com.xinl.easyclaw.base.orchestration.OrchestrationPlan;
import com.xinl.easyclaw.base.orchestration.StepExecutor;
import com.xinl.easyclaw.base.orchestration.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * {@link StepExecutor} 的生产实现：把 {@link AgentService#streamChat} 的三回调模型
 * （{@code onEvent / onError / onFinish}）桥接成一个 {@link CompletableFuture}。
 *
 * <h2>为什么放在 api 层</h2>
 * base 层刻意不认识 {@code StreamEvent}（它在 agent-core），编排契约只表达
 * 「异步 + 可取消」。事件往哪流、怎么落盘、怎么推 WebSocket，全部是 api 的职责。
 * 这样 mode 模块编译期够不到运行时设施，依赖方向保持单向。
 *
 * <h2>三条必须守住的既有语义</h2>
 * <ol>
 *   <li><b>流式不能断</b>：每个 delta 事件<b>立即</b>转发给 {@code eventSink}，
 *       不等步骤结束。future 只表示「这一步跑完了」，不承载增量内容。</li>
 *   <li><b>HITL 不能破</b>：{@code AgentService} 在挂起等待工具确认时<b>不会</b>调
 *       {@code onFinish}（见其 {@code doFinally} 中 {@code hasPendingConfirm} 分支，
 *       改为登记 pendingCallbacks 由 resume 流收尾）。因此把 future 的完成点绑在
 *       {@code onFinish} 上，天然继承了「确认期间不算完成」的语义 ——
 *       这不是巧合，是选择绑定 {@code onFinish} 而非 {@code end} 事件的<b>唯一原因</b>。</li>
 *   <li><b>停止要生效</b>：用户点停止走 {@code stopChat}，流被 dispose 后
 *       {@code doFinally} 仍会调 {@code onFinish}，future 正常完成，编排器随后在
 *       stage 边界看到 {@code CancelSignal} 置位而中止。不会挂死。</li>
 * </ol>
 *
 * <h2>为什么必须兜住「onFinish 一次都不来」</h2>
 * 上面第 2 条的代价是：一旦 {@code AgentService} 有任何路径既不 finish 也不 error，
 * future 就永久挂起，整个编排链卡死，前端停在「正在输出」。这是本类最危险的失败模式，
 * 因此 {@link #completeOnce} 用 CAS 保证恰好完成一次，且错误路径也会完成 future
 * （转为失败结果而非异常完成）——宁可报错，不可挂起。
 */
public class StreamingStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StreamingStepExecutor.class);

    private final AgentService agentService;
    private final Consumer<StreamEvent> eventSink;
    /** 用户本回合的附件；仅第一个步骤消费（后续步骤是智能体间接力，不再重复喂图） */
    private final List<UserAttachment> attachments;
    /** 用户本回合指定的 skill；同样仅第一个步骤消费 */
    private final String skillName;
    /** 步骤序号：用于判定「是否首个步骤」，决定附件/skill 是否透传 */
    private final AtomicInteger stepSeq = new AtomicInteger(0);

    /**
     * @param agentService 真正干活的对话服务
     * @param attachments  用户本回合附件，可为 null
     * @param skillName    用户本回合指定的 skill，可为 null
     * @param eventSink    事件出口，通常是 WebSocket 推送；每个 delta 实时转发
     */
    public StreamingStepExecutor(AgentService agentService,
                                 List<UserAttachment> attachments,
                                 String skillName,
                                 Consumer<StreamEvent> eventSink) {
        this.agentService = agentService;
        this.attachments = attachments;
        this.skillName = skillName;
        this.eventSink = eventSink;
    }

    @Override
    public CompletableFuture<StepResult> execute(OrchestrationPlan.PlanStep step,
                                                 ExecutionContext ctx) {
        CompletableFuture<StepResult> future = new CompletableFuture<>();
        // CAS 守卫：onError 之后 onFinish 仍会被调用（AgentService 的 doOnError 不阻断
        // doFinally），若不去重会触发 future 的第二次 complete —— 静默失效，且掩盖真实错误
        AtomicBoolean done = new AtomicBoolean(false);
        // 累积本步产出的正文：编排下游（如 team 的验收步）需要拿到上一步说了什么
        StringBuilder output = new StringBuilder();

        String instruction = step.instruction() == null || step.instruction().isBlank()
                ? ctx.task()
                : step.instruction();

        // 附件与 skill 属于「用户这次输入」，只归属首个步骤。多步计划里若每步都带，
        // 会把同一张图重复喂进上下文，且让后续步骤误以为用户又选了一次 skill。
        boolean firstStep = stepSeq.getAndIncrement() == 0;

        try {
            agentService.executeStep(
                    ctx.workspaceId(),
                    ctx.sessionId(),
                    instruction,
                    firstStep ? attachments : null,
                    firstStep ? skillName : null,
                    event -> {
                        // 先转发再累积：转发是主路径，累积失败不该影响用户看到输出
                        eventSink.accept(event);
                        if ("text".equals(event.type()) && event.content() != null) {
                            output.append(event.content());
                        }
                    },
                    err -> {
                        log.warn("[编排] 步骤执行出错: agent={}, session={}, err={}",
                                step.agentId(), ctx.sessionId(), String.valueOf(err));
                        completeOnce(done, future, StepResult.failure(
                                step.agentId(), describe(err)));
                    },
                    () -> completeOnce(done, future,
                            StepResult.success(step.agentId(), output.toString()))
            );
        } catch (RuntimeException e) {
            // executeStep 同步阶段就抛了（如工作区不存在）：future 还没被任何回调持有
            log.warn("[编排] 步骤派发失败: agent={}, err={}", step.agentId(), e.toString());
            completeOnce(done, future, StepResult.failure(step.agentId(), describe(e)));
        }
        return future;
    }

    /**
     * 恰好完成一次。
     * <p>
     * 失败也用 {@code complete} 而非 {@code completeExceptionally}：契约规定业务失败要表达为
     * {@code ok=false} 的正常完成，让编排器有机会决定返工还是中止。异常完成会绕过这个决策。
     */
    private void completeOnce(AtomicBoolean done,
                              CompletableFuture<StepResult> future,
                              StepResult result) {
        if (done.compareAndSet(false, true)) {
            // 回合收尾第二锚点：AGENT_END 已到但本条不出 = startStream 收尾链断
            // （续问流产前死亡 / finishTurn 被抑制）；本条出而「[编排] 执行完成」不出 = 编排链断
            log.info("[编排] 步骤 future 完成: agent={}, ok={}", result.agentId(), result.ok());
            future.complete(result);
        }
    }

    private static String describe(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }
}
