package com.xinl.easyclaw.base.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 默认编排执行引擎：<b>stage 之间串行、stage 之内并发、任一步失败即中止</b>。
 * <p>
 * 从 {@link AgentOrchestrator} 的 default 方法里拆出来独立成类，原因是异步串联需要
 * 累积可变状态（已完成结果列表、future 链），写在接口默认方法里既难读也难测；
 * 独立成类后可以直接对编排语义本身写单测，不必先造一个 orchestrator。
 *
 * <h2>为什么 stage 内是并发的</h2>
 * workflow 的 {@code parallel} 语义就是「与上一步并入同一组」，
 * 而 {@link OrchestrationPlan} 的 stage 分组已经承载了这个语义 ——
 * 同一 stage 内的步骤本就声明为可并行。同步实现时并发无从谈起（一步阻塞到底），
 * 异步化之后才第一次真正兑现这个承诺。
 *
 * <h2>失败与取消的处理差异</h2>
 * <ul>
 *   <li><b>失败</b>：当前 stage 内已派发的步骤<b>不强杀</b>，等它们自然结束后再中止后续
 *       stage。强杀会让工具执行到一半留下半个文件，比多跑几步危害大。</li>
 *   <li><b>取消</b>：只在 stage 边界与 step 派发前检查，已在执行中的步骤由执行器
 *       自己响应中断（{@code AgentService} 已有 stop 机制）。</li>
 * </ul>
 *
 * <h2>线程模型</h2>
 * 本类不创建线程，也不指定线程池：并发度完全来自 {@link StepExecutor} 返回的 future
 * 是否真的异步。这是刻意的 —— 执行体的线程归属（Spring 的任务执行器、WS 容器线程）
 * 属于 api 层的决策，base 层擅自 {@code supplyAsync} 会绕开调用方的线程治理。
 */
final class SequentialExecution {

    private SequentialExecution() {
    }

    /**
     * 按计划执行。
     *
     * @param modeId   模式标识，用于结果归属
     * @param plan     已确认可执行的计划
     * @param ctx      执行上下文
     * @param executor 步骤执行器
     * @return 执行结果 future，正常完成（业务失败也是正常完成）
     */
    static CompletableFuture<OrchestrationResult> run(String modeId,
                                                      OrchestrationPlan plan,
                                                      ExecutionContext ctx,
                                                      StepExecutor executor) {
        // 累积态：跨 stage 传递，只在编排链上单线程访问（stage 间串行），无需同步
        List<StepResult> collected = new ArrayList<>(plan.stepCount());
        CompletableFuture<OrchestrationResult> chain =
                CompletableFuture.completedFuture(null);

        for (List<OrchestrationPlan.PlanStep> stage : plan.stages()) {
            chain = chain.thenCompose(earlyExit -> {
                // 前序 stage 已经判定中止（失败或取消）：原样透传，不再派发
                if (earlyExit != null) {
                    return CompletableFuture.completedFuture(earlyExit);
                }
                if (ctx.isCancelled()) {
                    return CompletableFuture.completedFuture(
                            cancelled(modeId, collected));
                }
                return runStage(modeId, stage, ctx, executor, collected);
            });
        }

        return chain.thenApply(earlyExit -> earlyExit != null
                ? earlyExit
                : success(modeId, collected));
    }

    /**
     * 执行一个 stage：<b>当前为串行派发</b>，逐步等待前一步完成再派发下一步。
     * <p>
     * <b>为什么不是并发（尽管 stage 分组的语义就是「可并行」）</b>：
     * 生产执行器最终会落到 {@code AgentService.startStream}，而它对同一 {@code sessionId}
     * 强制「只保留一个活跃订阅」—— 发起新流前会 dispose 掉上一个（这是为了避免暂停流与
     * 确认恢复流重复推事件，是真业务）。因此同一会话内并发派发两步，第二步会直接掐掉第一步，
     * 表现为「其中一步的输出凭空消失」。
     * <p>
     * 真并发的前提是<b>先给并发步骤分配独立 sessionId</b>（涉及 SessionRegistry、转录落盘、
     * 前端事件归并三处联动），那是 team 模式接入时才必须解决的问题。single 模式恒为单步，
     * 并发对它零收益，不值得为此先动 session 模型。
     * <p>
     * <b>恢复并发的方法</b>：把下面的顺序链换回 {@code allOf(futures)} 即可 ——
     * 契约层（异步签名、取消、兜底）已经完全支持并发，缺的只是 session 隔离。
     */
    private static CompletableFuture<OrchestrationResult> runStage(
            String modeId,
            List<OrchestrationPlan.PlanStep> stage,
            ExecutionContext ctx,
            StepExecutor executor,
            List<StepResult> collected) {

        CompletableFuture<OrchestrationResult> chain =
                CompletableFuture.completedFuture(null);

        for (OrchestrationPlan.PlanStep step : stage) {
            chain = chain.thenCompose(earlyExit -> {
                if (earlyExit != null) {
                    return CompletableFuture.completedFuture(earlyExit);
                }
                // 步骤边界检查取消：串行派发下这里能及时止损，不会白跑后续步骤
                if (ctx.isCancelled()) {
                    return CompletableFuture.completedFuture(cancelled(modeId, collected));
                }
                return dispatch(step, ctx, executor).thenApply(r -> {
                    collected.add(r);
                    if (!r.ok()) {
                        // 默认策略「快速失败」：继续跑只会在错误上下文里产出更多垃圾。
                        // 需要容错或返工的模式请覆盖 AgentOrchestrator#execute。
                        return new OrchestrationResult(modeId, false, collected,
                                lastOutput(collected), errorsOf(collected));
                    }
                    return null; // null = 未提前退出
                });
            });
        }
        return chain;
    }

    /**
     * 派发单步，并兜住执行器的契约违约。
     * <p>
     * {@link StepExecutor} 约定「业务失败要正常完成而非抛异常」，但约定管不住实现 ——
     * 一旦实现方漏接异常，未处理的 future 会让整条编排链永久挂起（对话卡死无响应），
     * 这比失败本身严重得多。这里统一兜底转成失败结果。
     */
    private static CompletableFuture<StepResult> dispatch(OrchestrationPlan.PlanStep step,
                                                          ExecutionContext ctx,
                                                          StepExecutor executor) {
        CompletableFuture<StepResult> f;
        try {
            f = executor.execute(step, ctx);
        } catch (RuntimeException e) {
            // 同步抛出（连 future 都没建起来）
            return CompletableFuture.completedFuture(
                    StepResult.failure(step.agentId(), describe(e)));
        }
        if (f == null) {
            return CompletableFuture.completedFuture(
                    StepResult.failure(step.agentId(), "步骤执行器返回了 null future"));
        }
        return f.exceptionally(e -> StepResult.failure(step.agentId(), describe(e)));
    }

    private static String describe(Throwable e) {
        Throwable root = (e instanceof java.util.concurrent.CompletionException
                && e.getCause() != null) ? e.getCause() : e;
        String msg = root.getMessage();
        return msg == null || msg.isBlank()
                ? root.getClass().getSimpleName()
                : root.getClass().getSimpleName() + ": " + msg;
    }

    private static OrchestrationResult success(String modeId, List<StepResult> collected) {
        return new OrchestrationResult(modeId, true, collected,
                lastOutput(collected), List.of());
    }

    private static OrchestrationResult cancelled(String modeId, List<StepResult> collected) {
        return new OrchestrationResult(modeId, false, collected,
                lastOutput(collected), List.of("执行已被取消"));
    }

    private static String lastOutput(List<StepResult> collected) {
        return collected.isEmpty() ? "" : collected.get(collected.size() - 1).output();
    }

    private static List<String> errorsOf(List<StepResult> collected) {
        List<String> errors = new ArrayList<>();
        for (StepResult r : collected) {
            if (!r.ok() && r.error() != null) {
                errors.add(r.error());
            }
        }
        return errors;
    }
}
