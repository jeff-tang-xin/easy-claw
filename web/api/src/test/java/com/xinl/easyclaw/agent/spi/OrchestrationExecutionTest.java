package com.xinl.easyclaw.agent.spi;

import com.xinl.easyclaw.base.orchestration.AgentOrchestrator;
import com.xinl.easyclaw.base.orchestration.CancelSignal;
import com.xinl.easyclaw.base.orchestration.ExecutionContext;
import com.xinl.easyclaw.base.orchestration.OrchestrationPlan;
import com.xinl.easyclaw.base.orchestration.OrchestrationResult;
import com.xinl.easyclaw.base.orchestration.StepExecutor;
import com.xinl.easyclaw.base.orchestration.StepResult;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排执行通路测试 —— 验证 {@code plan() → execute() → StepExecutor} 真的被串起来。
 * <p>
 * <b>为什么必须有这组测试</b>：改造前 {@code plan()} 是死代码 —— 编译通过、
 * SPI 装配成功、单测断言计划结构正确，但业务代码从不调用它。
 * 「结构正确」不等于「通路接通」，只有断言 {@link StepExecutor} 真的被回调、
 * 且回调参数正确，才能证明执行链没有断在中间。
 */
class OrchestrationExecutionTest {

    /** 记录被执行的步骤，用于断言编排顺序 */
    private static class RecordingExecutor implements StepExecutor {
        final List<String> executed = new CopyOnWriteArrayList<>();
        private final boolean failFirst;

        RecordingExecutor() {
            this(false);
        }

        RecordingExecutor(boolean failFirst) {
            this.failFirst = failFirst;
        }

        @Override
        public CompletableFuture<StepResult> execute(OrchestrationPlan.PlanStep step,
                                                     ExecutionContext ctx) {
            executed.add(step.agentId() + ":" + step.instruction());
            if (failFirst && executed.size() == 1) {
                return CompletableFuture.completedFuture(
                        StepResult.failure(step.agentId(), "模拟执行失败"));
            }
            return CompletableFuture.completedFuture(
                    StepResult.success(step.agentId(), "done-" + step.agentId()));
        }
    }

    /** 等待 future 结果，超时即判失败（防止编排链永久挂起被误判为通过） */
    private static OrchestrationResult await(CompletableFuture<OrchestrationResult> f) {
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("编排未在 5s 内完成，可能是 future 永久挂起: " + e, e);
        }
    }

    private static ScenarioEntity scenario(String mode, String role, String workflow) {
        ScenarioEntity s = new ScenarioEntity();
        s.setName("s1");
        s.setMode(mode);
        s.setRoleName(role);
        s.setWorkflow(workflow);
        return s;
    }

    @Test
    @DisplayName("single：plan 产出的步骤真的被 StepExecutor 执行，且 agentId 取自场景角色")
    void singleModeActuallyExecutesStep() {
        OrchestratorRegistry registry = new OrchestratorRegistry();
        RecordingExecutor executor = new RecordingExecutor();

        ExecutionContext ctx = ExecutionContext.of(
                "ws-1", scenario("single", "coder", null), "写个函数");
        OrchestrationResult result = await(registry.execute(ctx, executor));

        assertTrue(result.ok(), "single 执行应成功");
        assertEquals(List.of("coder:写个函数"), executor.executed,
                "应恰好执行一步，agentId 取自场景 roleName，指令为用户任务原文");
        assertEquals("done-coder", result.finalOutput());
        assertEquals(1, result.stepResults().size());
    }

    @Test
    @DisplayName("single：场景未绑角色时回退 main")
    void singleModeFallsBackToMain() {
        OrchestratorRegistry registry = new OrchestratorRegistry();
        RecordingExecutor executor = new RecordingExecutor();

        await(registry.execute(ExecutionContext.of("ws-1", null, "干活"), executor));

        assertEquals(List.of("main:干活"), executor.executed,
                "无场景绑定时应回退到 main 智能体");
    }

    @Test
    @DisplayName("默认实现：某步失败即中止，后续步骤不再执行")
    void failFastStopsRemainingSteps() {
        AgentOrchestrator team = new OrchestratorRegistry().find("team").orElseThrow();
        RecordingExecutor executor = new RecordingExecutor(true);

        String workflow = """
                {"steps":[{"role":"planner","instruction":"出方案"},
                          {"role":"coder","instruction":"写代码"}]}""";
        ExecutionContext ctx = ExecutionContext.of(
                "ws-1", scenario("team", null, workflow), "任务");
        OrchestrationResult result = await(team.execute(ctx, executor));

        assertFalse(result.ok(), "首步失败后整体应失败");
        assertEquals(1, executor.executed.size(),
                "首步失败后不应继续执行第二步（默认快速失败策略）");
        assertEquals(1, result.failures().size());
    }

    @Test
    @DisplayName("计划不可执行时不调用 StepExecutor（team 缺场景）")
    void rejectedPlanNeverTouchesExecutor() {
        AgentOrchestrator team = new OrchestratorRegistry().find("team").orElseThrow();
        RecordingExecutor executor = new RecordingExecutor();

        OrchestrationResult result = await(team.execute(
                ExecutionContext.of("ws-1", null, "任务"), executor));

        assertFalse(result.ok());
        assertTrue(executor.executed.isEmpty(),
                "计划不可执行时不应触发任何实际执行");
        assertFalse(result.errors().isEmpty(), "应保留计划阶段的错误说明");
    }

    @Test
    @DisplayName("truncated 步骤不被误判为失败，但可被上层识别")
    void truncationIsVisibleButNotFailure() {
        StepResult r = StepResult.truncated("coder", "写了一半");
        OrchestrationResult result = OrchestrationResult.of("single", r);

        assertTrue(result.ok(), "截断是「产出可能不完整」而非失败");
        assertTrue(result.hasTruncation(), "上层必须能识别出存在截断");
    }

    // ==================== 异步化专属用例 ====================
    // 以下场景在同步签名下根本无法表达，是本次改造的核心价值所在

    @Test
    @DisplayName("异步：执行器不阻塞调用线程，编排立即返回 future")
    void executorDoesNotBlockCallingThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        StepExecutor slow = (step, ctx) -> CompletableFuture.supplyAsync(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return StepResult.success(step.agentId(), "late");
        });

        CompletableFuture<OrchestrationResult> f = new OrchestratorRegistry()
                .execute(ExecutionContext.of("ws-1", null, "慢活"), slow);

        // 执行器仍卡着，但 execute() 必须已经返回 —— 这正是流式输出得以保留的前提
        assertFalse(f.isDone(), "execute() 不应阻塞到步骤完成");
        release.countDown();
        assertEquals("late", await(f).finalOutput());
    }

    @Test
    @DisplayName("同 stage 步骤当前为串行派发：上一步未完成前，下一步不得被派发")
    void sameStageStepsAreDispatchedSerially() throws Exception {
        // 【这条测试是哨兵，不是能力验证】
        // stage 分组的语义是「可并行」，契约层也支持并发（把 runStage 换回 allOf 即可）。
        // 但生产执行器落到 AgentService.startStream，它对同一 sessionId 只保留一个活跃订阅，
        // 并发派发会让第二步 dispose 掉第一步的流 —— 表现为「某一步输出凭空消失」，
        // 且不报错、极难定位。在给并发步骤分配独立 sessionId 之前，串行是唯一安全选择。
        // 若有人改回并发而未先解决 session 隔离，这条测试会立刻变红。
        AtomicBoolean firstRunning = new AtomicBoolean(false);
        AtomicBoolean overlapped = new AtomicBoolean(false);
        CountDownLatch firstEntered = new CountDownLatch(1);

        StepExecutor probe = (step, ctx) -> {
            if (!firstRunning.compareAndSet(false, true)) {
                overlapped.set(true); // 前一步尚未归位就进来了 = 发生了并发
            }
            firstEntered.countDown();
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(50); // 留出足以观察到重叠的窗口
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                firstRunning.set(false);
                return StepResult.success(step.agentId(), "ok");
            });
        };

        // parallel=true 使第二步并入第一步所在 stage
        String workflow = """
                {"steps":[{"role":"a","instruction":"x"},
                          {"role":"b","instruction":"y","parallel":true}]}""";
        AgentOrchestrator team = new OrchestratorRegistry().find("team").orElseThrow();
        OrchestrationResult result = await(team.execute(
                ExecutionContext.of("ws-1", scenario("team", null, workflow), "任务"),
                probe));

        assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
        assertTrue(result.ok());
        assertEquals(2, result.stepResults().size(), "两步都要执行，只是不并发");
        assertFalse(overlapped.get(),
                "检测到同 stage 步骤并发执行；共享 sessionId 下这会导致前一步的流被 dispose");
    }

    @Test
    @DisplayName("取消：信号置位后不再派发后续 stage")
    void cancelStopsSubsequentStages() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        RecordingExecutor executor = new RecordingExecutor() {
            @Override
            public CompletableFuture<StepResult> execute(OrchestrationPlan.PlanStep step,
                                                         ExecutionContext ctx) {
                cancelled.set(true); // 第一步执行完即模拟用户点了停止
                return super.execute(step, ctx);
            }
        };

        // 两个串行 stage（无 parallel 标记）
        String workflow = """
                {"steps":[{"role":"first","instruction":"x"},
                          {"role":"second","instruction":"y"}]}""";
        AgentOrchestrator team = new OrchestratorRegistry().find("team").orElseThrow();
        ExecutionContext ctx = new ExecutionContext("ws-1", "s-1",
                scenario("team", null, workflow), "任务", cancelled::get);

        OrchestrationResult result = await(team.execute(ctx, executor));

        assertFalse(result.ok(), "被取消的编排不应报告成功");
        assertEquals(1, executor.executed.size(),
                "取消后不应派发第二个 stage");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("取消")),
                "应明确告知是取消而非普通失败，实际: " + result.errors());
    }

    @Test
    @DisplayName("兜底：执行器 future 异常完成时转为失败结果，不让编排链挂死")
    void exceptionalFutureBecomesFailure() {
        StepExecutor broken = (step, ctx) ->
                CompletableFuture.failedFuture(new IllegalStateException("运行时组装失败"));

        OrchestrationResult result = await(new OrchestratorRegistry()
                .execute(ExecutionContext.of("ws-1", null, "任务"), broken));

        assertFalse(result.ok());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).error().contains("运行时组装失败"),
                "应保留原始异常信息便于定位，实际: " + result.failures().get(0).error());
    }

    @Test
    @DisplayName("兜底：执行器同步抛异常或返回 null 时不拖垮编排")
    void misbehavingExecutorIsContained() {
        StepExecutor thrower = (step, ctx) -> {
            throw new IllegalStateException("执行器自己炸了");
        };
        OrchestrationResult r1 = await(new OrchestratorRegistry()
                .execute(ExecutionContext.of("ws-1", null, "任务"), thrower));
        assertFalse(r1.ok(), "同步抛异常应被兜住转为失败");
        assertNotNull(r1.failures().get(0).error());

        StepExecutor nullReturner = (step, ctx) -> null;
        OrchestrationResult r2 = await(new OrchestratorRegistry()
                .execute(ExecutionContext.of("ws-1", null, "任务"), nullReturner));
        assertFalse(r2.ok(), "返回 null future 应被兜住，而不是 NPE 或永久挂起");
    }
}
