package com.xinl.easyclaw.agent.orchestrator;

import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.base.orchestration.ExecutionContext;
import com.xinl.easyclaw.base.orchestration.OrchestrationModes;
import com.xinl.easyclaw.base.orchestration.OrchestrationPlan.PlanStep;
import com.xinl.easyclaw.base.orchestration.StepResult;
import com.xinl.easyclaw.base.profile.ScenarioProfile;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

    // ==================== T1 确定性 team 流水线：子智能体直派路由 ====================
    //
    // 这组用例守的是 T1 的命门：只在「编排型模式(team) + 步骤显式指定非主控 agentId」时
    // 才把步骤直派给 invokeSubagent；其余一切（single / 主控步 / 未指定 agentId）必须
    // 原封不动走 executeStep。任何一条红线被破坏，线上 single 行为就会变。
    //
    // mock 回调参数索引（7 参 invokeSubagent(wsId, parentSessionId, agentId, instruction,
    // onEvent, onError, onFinish)）对应 4 / 5 / 6。

    /** 造一个只设定 mode 的场景视图（其余字段本测试不关心，全为 null） */
    private static ScenarioProfile scenarioWithMode(String mode) {
        return new ScenarioProfile() {
            @Override public String getName() { return null; }
            @Override public String getDisplayName() { return null; }
            @Override public String getIcon() { return null; }
            @Override public String getDescription() { return null; }
            @Override public String getSystemPrompt() { return null; }
            @Override public String getMode() { return mode; }
            @Override public String getWorkflow() { return null; }
            @Override public String getSkills() { return null; }
            @Override public String getSubagents() { return null; }
            @Override public String getMcpServices() { return null; }
            @Override public String getCapabilityTier() { return null; }
            @Override public String getRoleName() { return null; }
        };
    }

    private static final ExecutionContext TEAM_CTX = new ExecutionContext(
            "ws-1", "s-1", scenarioWithMode("team"), "原始任务", () -> false);

    @Test
    @DisplayName("T1 路由：team + 非主控子智能体 → 直派 invokeSubagent，executeStep 零调用")
    void teamModeRoutesNonMainAgentToSubagent() {
        // 前置守卫：web/api 测试 classpath 必须经 ServiceLoader 真发现 team 模式，
        // 否则 isOrchestrated 恒为 false，下面的「走了直派」断言会变成无意义的假阴性/假阳性。
        assertTrue(OrchestrationModes.isOrchestrated("team"),
                "测试 classpath 缺少 mode-team 的 SPI 注册，路由判定不可信");

        List<StreamEvent> forwarded = new CopyOnWriteArrayList<>();
        StreamingStepExecutor exe = new StreamingStepExecutor(
                agentService, null, null, forwarded::add, true);

        doAnswer(inv -> {
            Consumer<StreamEvent> onEvent = inv.getArgument(4);
            Consumer<String> onFinish = inv.getArgument(6);
            onEvent.accept(StreamEvent.text("子智能体产出片段"));
            onFinish.accept("子智能体最终回复");
            return null;
        }).when(agentService).invokeSubagent(eq("ws-1"), eq("s-1"), eq("coder"), eq("写个排序"),
                any(), any(), any());

        StepResult r = await(exe.execute(new PlanStep("coder", "写个排序"), TEAM_CTX));

        assertTrue(r.ok());
        assertEquals("coder", r.agentId());
        assertEquals("子智能体最终回复", r.output(),
                "子智能体步骤产出取 onFinish 回传文本，供 T2 编排下游 / 黑板传递使用");
        assertEquals(1, forwarded.size(), "子智能体事件透传前端（折叠卡片由 AgentService 侧负责）");
        verify(agentService, never()).executeStep(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("T1 红线（最关键）：开关关（T1 默认）+ team + coder → 仍走 executeStep，线上 team 零变化")
    void disabledSwitchKeepsTeamStepsOnMainPath() {
        // 生产构造点用的是默认关闭的 4 参构造（见 AgentService.streamChat）。
        // 内置 team 场景 team-dev/code-review 的 workflow 每步都带 coder/reviewer 等子角色，
        // 此用例守死「T1 未启用前，这些步骤绝不能切到 invokeSubagent」——否则重启即改线上行为。
        StreamingStepExecutor exe = new StreamingStepExecutor(agentService, null, null, ev -> { });

        doAnswer(inv -> {
            inv.getArgument(7, Runnable.class).run();
            return null;
        }).when(agentService).executeStep(eq("ws-1"), eq("s-1"), eq("写个排序"),
                any(), any(), any(), any(), any());

        StepResult r = await(exe.execute(new PlanStep("coder", "写个排序"), TEAM_CTX));

        assertTrue(r.ok(), "开关关闭时 team 子角色步骤产出仍由主控路径完成");
        verify(agentService, never()).invokeSubagent(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("T1 路由：team 模式但步骤指定主控 main → 仍走 executeStep，不直派")
    void teamModeMainAgentStaysOnMainPath() {
        StreamingStepExecutor exe = new StreamingStepExecutor(
                agentService, null, null, ev -> { }, true);

        doAnswer(inv -> {
            inv.getArgument(7, Runnable.class).run();
            return null;
        }).when(agentService).executeStep(eq("ws-1"), eq("s-1"), eq("审核"),
                any(), any(), any(), any(), any());

        StepResult r = await(exe.execute(new PlanStep("main", "审核"), TEAM_CTX));

        assertTrue(r.ok());
        verify(agentService, never()).invokeSubagent(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("T1 路由：team 模式但步骤指定主控 MAIN（大小写不敏感）→ 走 executeStep，不直派")
    void teamModeMainAgentCaseInsensitiveStaysOnMainPath() {
        StreamingStepExecutor exe = new StreamingStepExecutor(
                agentService, null, null, ev -> { }, true);

        doAnswer(inv -> {
            inv.getArgument(7, Runnable.class).run();
            return null;
        }).when(agentService).executeStep(eq("ws-1"), eq("s-1"), eq("审核"),
                any(), any(), any(), any(), any());

        // PlanStep 构造器已保证 agentId 非空白，故「空白 agentId」真实不可达；
        // 这里验证大小写不敏感的主控判定：MAIN 与 main 同等对待。
        StepResult r = await(exe.execute(new PlanStep("MAIN", "审核"), TEAM_CTX));

        assertTrue(r.ok());
        verify(agentService, never()).invokeSubagent(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("T1 红线：single 模式即便步骤写了非主控 agentId(coder) 也走 executeStep，绝不直派")
    void singleModeNeverRoutesToSubagent() {
        ExecutionContext singleCtx = new ExecutionContext(
                "ws-1", "s-1", scenarioWithMode("single"), "原始任务", () -> false);
        // 刻意把开关打开：证明即便启用了直派，single 仍被 isOrchestrated 判据挡住，不依赖开关。
        StreamingStepExecutor exe = new StreamingStepExecutor(
                agentService, null, null, ev -> { }, true);

        doAnswer(inv -> {
            inv.getArgument(7, Runnable.class).run();
            return null;
        }).when(agentService).executeStep(eq("ws-1"), eq("s-1"), eq("写个排序"),
                any(), any(), any(), any(), any());

        StepResult r = await(exe.execute(new PlanStep("coder", "写个排序"), singleCtx));

        assertTrue(r.ok(), "single 下步骤产出仍由主控路径完成");
        verify(agentService, never()).invokeSubagent(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("T1 子智能体 onError 转为失败结果（正常完成、ok=false），不炸到编排器")
    void subagentOnErrorBecomesFailureResult() {
        StreamingStepExecutor exe = new StreamingStepExecutor(
                agentService, null, null, ev -> { }, true);

        doAnswer(inv -> {
            Consumer<Throwable> onError = inv.getArgument(5);
            onError.accept(new RuntimeException("子智能体执行失败 X"));
            return null;
        }).when(agentService).invokeSubagent(eq("ws-1"), eq("s-1"), eq("coder"), eq("写个排序"),
                any(), any(), any());

        StepResult r = await(exe.execute(new PlanStep("coder", "写个排序"), TEAM_CTX));

        assertFalse(r.ok(), "失败以 ok=false 的正常完成表达，交给 T2 编排器决定返工/中止");
        assertEquals("coder", r.agentId());
        assertTrue(r.error().contains("X"), "应保留原始错误信息，实际: " + r.error());
        verify(agentService, never()).executeStep(
                any(), any(), any(), any(), any(), any(), any(), any());
    }
}
