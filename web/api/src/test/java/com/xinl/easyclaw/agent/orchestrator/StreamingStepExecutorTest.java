package com.xinl.easyclaw.agent.orchestrator;

import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.base.orchestration.ExecutionContext;
import com.xinl.easyclaw.base.orchestration.OrchestrationPlan.PlanStep;
import com.xinl.easyclaw.base.orchestration.StepResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * {@link StreamingStepExecutor} 的行为验证。
 * <p>
 * 重点不在「能跑通」，而在守住三条既有语义：<b>流式实时转发、HITL 期间不完成、
 * 任何路径都不永久挂起</b>。所有 future 等待都带超时，挂起会判失败而非静默通过 ——
 * 挂起是本类最危险的失败模式（表现为对话卡在「正在输出」）。
 * <p>
 * mock 的回调参数索引：8 参 {@code executeStep(wsId, sesId, instruction, attachments,
 * skillName, onEvent, onError, onFinish)} 对应 <b>5 / 6 / 7</b>。
 */
@ExtendWith(MockitoExtension.class)
class StreamingStepExecutorTest {

    private static final PlanStep STEP = new PlanStep("coder", "写个排序");
    private static final ExecutionContext CTX = new ExecutionContext(
            "ws-1", "s-1", null, "原始任务", () -> false);

    @Mock
    private AgentService agentService;

    /** 等待 future，超时即判失败——防止「永久挂起」被误当通过 */
    private static StepResult await(CompletableFuture<StepResult> f) {
        try {
            return f.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("future 未在 3s 内完成，疑似永久挂起: " + e, e);
        }
    }

    @Test
    @DisplayName("onFinish 完成 future，正文按序累积为 output")
    void onFinishCompletesWithAccumulatedOutput() {
        List<StreamEvent> forwarded = new CopyOnWriteArrayList<>();
        StreamingStepExecutor exe = new StreamingStepExecutor(agentService, null, null, forwarded::add);

        doAnswer(inv -> {
            Consumer<StreamEvent> onEvent = inv.getArgument(5);
            Runnable onFinish = inv.getArgument(7);
            onEvent.accept(StreamEvent.text("hello "));
            onEvent.accept(StreamEvent.reasoning("（思考）"));
            onEvent.accept(StreamEvent.text("world"));
            onFinish.run();
            return null;
        }).when(agentService).executeStep(eq("ws-1"), eq("s-1"), eq("写个排序"),
                any(), any(), any(), any(), any());

        StepResult r = await(exe.execute(STEP, CTX));

        assertTrue(r.ok());
        assertEquals("coder", r.agentId());
        assertEquals("hello world", r.output(),
                "只累积 text，reasoning 不应混入交给下游的产出");
        assertEquals(3, forwarded.size(), "所有事件都要转发，包括 reasoning");
    }

    @Test
    @DisplayName("流式实时性：delta 在 future 完成前就已转发，不被攒到最后")
    void deltasForwardedBeforeCompletion() {
        List<StreamEvent> forwarded = new CopyOnWriteArrayList<>();
        StreamingStepExecutor exe = new StreamingStepExecutor(agentService, null, null, forwarded::add);

        doAnswer(inv -> {
            Consumer<StreamEvent> onEvent = inv.getArgument(5);
            Runnable onFinish = inv.getArgument(7);
            onEvent.accept(StreamEvent.text("第一个字"));
            // 关键断言点：此刻 onFinish 尚未调用，但事件必须已经出去了。
            // 这正是「异步化没有牺牲流式体验」的直接证据。
            assertEquals(1, forwarded.size(), "delta 必须立即转发，不能等步骤结束");
            onFinish.run();
            return null;
        }).when(agentService).executeStep(eq("ws-1"), eq("s-1"), eq("写个排序"),
                any(), any(), any(), any(), any());

        assertTrue(await(exe.execute(STEP, CTX)).ok());
    }

    @Test
    @DisplayName("HITL：挂起等待确认期间（未调 onFinish）future 保持未完成")
    void pendingConfirmKeepsFutureIncomplete() throws Exception {
        // 复刻 AgentService 的真实行为：hasPendingConfirm 时 doFinally 不调 onFinish，
        // 而是登记回调，待 resume 流收尾。future 必须跟着一起等，否则编排器会以为
        // 这步已经跑完，抢在用户确认之前推进下一步。
        CountDownLatch resumeReady = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Runnable> pendingFinish =
                new java.util.concurrent.atomic.AtomicReference<>();
        StreamingStepExecutor exe = new StreamingStepExecutor(agentService, null, null, ev -> {});

        doAnswer(inv -> {
            Consumer<StreamEvent> onEvent = inv.getArgument(5);
            onEvent.accept(StreamEvent.text("我要调用写文件工具"));
            // 模拟挂起：只登记，不调用
            pendingFinish.set(inv.getArgument(7));
            resumeReady.countDown();
            return null;
        }).when(agentService).executeStep(eq("ws-1"), eq("s-1"), eq("写个排序"),
                any(), any(), any(), any(), any());

        CompletableFuture<StepResult> f = exe.execute(STEP, CTX);
        assertTrue(resumeReady.await(3, TimeUnit.SECONDS));
        assertFalse(f.isDone(), "等待用户确认期间，这一步不算完成");

        // 用户点了确认 → resume 流最终收尾
        pendingFinish.get().run();
        assertTrue(await(f).ok(), "确认后 future 应正常完成");
    }

    @Test
    @DisplayName("onError 转为失败结果（而非异常完成），保留原始错误信息")
    void onErrorBecomesFailureResult() {
        StreamingStepExecutor exe = new StreamingStepExecutor(agentService, null, null, ev -> {});

        doAnswer(inv -> {
            Consumer<Throwable> onError = inv.getArgument(6);
            Runnable onFinish = inv.getArgument(7);
            onError.accept(new RuntimeException("模型 API 返回 429"));
            onFinish.run(); // 真实行为：doOnError 不阻断 doFinally
            return null;
        }).when(agentService).executeStep(eq("ws-1"), eq("s-1"), eq("写个排序"),
                any(), any(), any(), any(), any());

        StepResult r = await(exe.execute(STEP, CTX));

        assertFalse(r.ok(), "CAS 应让先到的 onError 定调，不被随后的 onFinish 翻成成功");
        assertTrue(r.error().contains("429"), "应保留原始错误信息，实际: " + r.error());
    }

    @Test
    @DisplayName("streamChat 同步抛异常被兜住，不炸到编排器")
    void syncThrowIsContained() {
        StreamingStepExecutor exe = new StreamingStepExecutor(agentService, null, null, ev -> {});

        doThrow(new IllegalStateException("工作区不存在"))
                .when(agentService).executeStep(eq("ws-1"), eq("s-1"), eq("写个排序"),
                        any(), any(), any(), any(), any());

        StepResult r = await(exe.execute(STEP, CTX));

        assertFalse(r.ok());
        assertTrue(r.error().contains("工作区不存在"), "实际: " + r.error());
    }

    @Test
    @DisplayName("instruction 为空时回退到 ctx.task()")
    void blankInstructionFallsBackToTask() {
        StreamingStepExecutor exe = new StreamingStepExecutor(agentService, null, null, ev -> {});

        doAnswer(inv -> {
            Runnable onFinish = inv.getArgument(7);
            onFinish.run();
            return null;
        }).when(agentService).executeStep(eq("ws-1"), eq("s-1"), eq("原始任务"),
                any(), any(), any(), any(), any());

        // 期望值 eq("原始任务") 就是断言本身：若传的是空串，mock 不匹配 → 无回调 → 超时失败
        assertTrue(await(exe.execute(new PlanStep("coder", "  "), CTX)).ok());
    }
}
