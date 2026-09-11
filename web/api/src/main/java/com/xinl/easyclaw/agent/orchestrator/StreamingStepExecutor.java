package com.xinl.easyclaw.agent.orchestrator;

import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.agent.domain.UserAttachment;
import com.xinl.easyclaw.base.orchestration.ExecutionContext;
import com.xinl.easyclaw.base.orchestration.OrchestrationModes;
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
    /**
     * 确定性 team 流水线开关（T1 地基）。
     * <p>
     * <b>默认 false</b>：关闭时所有步骤（含 team workflow 里的子角色步骤）一律走主控
     * {@code executeStep}，线上 single/team 行为与历史<b>逐字节一致</b>。这是刻意的安全边界——
     * 内置场景 {@code team-dev} / {@code code-review} 的 workflow 每步都带 planner/coder/reviewer，
     * 一旦无条件启用，线上 team 重启后立刻从「主控跑全部步骤」切到「真子智能体逐步骤直派」，
     * 而该路径要等 T2 配齐黑板唯一通信 / 审核门禁 / 返工上限 / 中止并完成 E2E 后才允许生效。
     * <p>
     * <b>这是临时开关</b>：T2 完成确定性 team 编排后，生产构造点改为 true 并删除本开关与
     * 4 参重载，使直派成为 team 的唯一行为。用实例字段而非静态开关，避免并行测试互相污染。
     */
    private final boolean deterministicDispatchEnabled;
    /** 步骤序号：用于判定「是否首个步骤」，决定附件/skill 是否透传 */
    private final AtomicInteger stepSeq = new AtomicInteger(0);

    /**
     * 历史 4 参构造：确定性直派默认关闭，保持线上行为零变化。
     * 既有调用方与单测无需改动；T2 启用后本重载随开关一并删除。
     */
    public StreamingStepExecutor(AgentService agentService,
                                 List<UserAttachment> attachments,
                                 String skillName,
                                 Consumer<StreamEvent> eventSink) {
        this(agentService, attachments, skillName, eventSink, false);
    }

    /**
     * @param agentService                真正干活的对话服务
     * @param attachments                 用户本回合附件，可为 null
     * @param skillName                   用户本回合指定的 skill，可为 null
     * @param eventSink                   事件出口，通常是 WebSocket 推送；每个 delta 实时转发
     * @param deterministicDispatchEnabled 是否启用 team 子角色步骤确定性直派（T1 默认关，T2 打开）
     */
    public StreamingStepExecutor(AgentService agentService,
                                 List<UserAttachment> attachments,
                                 String skillName,
                                 Consumer<StreamEvent> eventSink,
                                 boolean deterministicDispatchEnabled) {
        this.agentService = agentService;
        this.attachments = attachments;
        this.skillName = skillName;
        this.eventSink = eventSink;
        this.deterministicDispatchEnabled = deterministicDispatchEnabled;
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

        // 附件与 skill 属于「用户这次输入」，只归属首个步骤。序号自增放在路由之前，
        // 保证「主控步 + 子智能体步」混合计划里 firstStep 判定不因分支提前 return 而错位。
        // （T1 直派子智能体暂不透传附件/skill，见 invokeSubagentStep。）
        boolean firstStep = stepSeq.getAndIncrement() == 0;

        // T1 确定性 team 编排路由：仅当开关开启且处于编排型模式（team）、步骤显式指定了
        // 非主控子智能体时，才直派该 SPI 子智能体；开关关闭（T1 默认）/ single /
        // 未绑定场景（modeId=null）/ 未指定 agentId / 指定 main 一律走原主控路径，
        // 行为与历史完全一致。详见 resolveSubagent 的说明。
        String routedAgent = resolveSubagent(step, ctx);
        if (routedAgent != null) {
            return invokeSubagentStep(step, ctx, routedAgent, instruction);
        }

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
     * 把一个编排步骤直派给指定的 SPI 子智能体（T1 确定性 team 流水线地基）。
     * <p>
     * 事件全部经 {@code eventSink} 实时转发（子智能体的文本/思考/工具已由
     * {@code AgentService.invokeSubagent} 折叠成 subagent_* 协议事件，这里只透传，
     * <b>不</b>再累积 {@code text}——子智能体正文不是主控正文，混进 output 会双重计算）；
     * 本步产出取子智能体的最终回复（{@code onFinish} 回传的文本），作为下一步的
     * {@code previousOutput}。失败/未注册统一表达为 {@code ok=false} 的正常完成，
     * 交给编排器决定返工或中止（T2 消费）。
     */
    private CompletableFuture<StepResult> invokeSubagentStep(OrchestrationPlan.PlanStep step,
                                                             ExecutionContext ctx,
                                                             String agentId,
                                                             String instruction) {
        CompletableFuture<StepResult> future = new CompletableFuture<>();
        AtomicBoolean done = new AtomicBoolean(false);
        try {
            agentService.invokeSubagent(
                    ctx.workspaceId(),
                    ctx.sessionId(),
                    agentId,
                    instruction,
                    eventSink::accept,
                    err -> {
                        log.warn("[编排] 子智能体步骤出错: agent={}, session={}, err={}",
                                agentId, ctx.sessionId(), String.valueOf(err));
                        completeOnce(done, future, StepResult.failure(agentId, describe(err)));
                    },
                    resultText -> completeOnce(done, future,
                            StepResult.success(agentId, resultText))
            );
        } catch (RuntimeException e) {
            // 同步派发阶段抛错（工作区不存在 / 主控未装配管理器 / 未注册子智能体由内部回调兜，
            // 这里只兜未预期的同步异常）
            log.warn("[编排] 子智能体步骤派发失败: agent={}, err={}", agentId, e.toString());
            completeOnce(done, future, StepResult.failure(agentId, describe(e)));
        }
        return future;
    }

    /**
     * 判定某步骤是否应直派子智能体，返回其 agentId；不满足返回 {@code null}（走主控）。
     * <p>
     * 四个条件缺一不可，刻意从严：
     * <ol>
     *   <li><b>确定性直派开关已打开</b>（{@link #deterministicDispatchEnabled}）：T1 默认关，
     *       关闭时直接返回 null，线上 team 维持「主控跑全部步骤」的历史行为；</li>
     *   <li><b>必须是编排型模式</b>：用 {@link OrchestrationModes#isOrchestrated(String)} 判定，
     *       不硬编码 "team"，未来 schedule 等编排模式自动适用；single / modeId 为 null（未绑定
     *       场景，既有单测与非编排调用）一律 false，<b>线上 single 行为零变化</b>；</li>
     *   <li>步骤必须显式指定非空 agentId（空串/null 表示「主控/未指定」）；</li>
     *   <li>agentId 不等于主控 id {@code "main"}（忽略大小写）。</li>
     * </ol>
     * 这里<b>不</b>校验 agentId 是否真已注册——注册校验在
     * {@link AgentService#invokeSubagent} 经 {@code createAgentIfPresent} 完成，
     * 未注册会以明确的 {@code ok=false} 结束该步，而非静默回退主控（静默回退会掩盖
     * workflow 配置错误，让「配了子智能体却跑了主控」这种故障无声发生）。
     */
    private String resolveSubagent(OrchestrationPlan.PlanStep step, ExecutionContext ctx) {
        if (!deterministicDispatchEnabled) {
            return null;
        }
        if (!OrchestrationModes.isOrchestrated(ctx.modeId())) {
            return null;
        }
        String agentId = step.agentId();
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        agentId = agentId.trim();
        if ("main".equalsIgnoreCase(agentId)) {
            return null;
        }
        return agentId;
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
