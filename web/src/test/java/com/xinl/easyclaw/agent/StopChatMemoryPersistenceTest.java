package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.config.AppConstants;
import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.workspace.ScenarioResolver;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import io.agentscope.core.ReActAgent;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺陷复现：用户主动停止时，停止前已执行的内容与用户提问没有进入 Agent 记忆。
 * <p>
 * 【根因】AgentScope 中断路径上**唯一**把会话记忆写盘的地方是
 * {@code ReActAgent.handleInterrupt}（ReActAgent.java:4046 的 saveStateToSession），
 * 而它只在 {@code AgentBase.createErrorHandler}（AgentBase.java:497-499）捕获到
 * {@code InterruptedException} 时才被调用。Reactor 的 cancel **不是 error**：
 * 若 {@code stopChat} 先 {@code dispose()} 再 {@code interrupt()}，订阅被静默掐断，
 * 中断异常永不产生 → handleInterrupt 从不执行 → 本轮上下文全部丢失，
 * 下一轮 {@code activateSlotForContext} 又从 store 强制重载覆盖内存，造成「彻底失忆」。
 * <p>
 * 因此本测试锁定的是**调用顺序契约**：interrupt 必须先于 dispose，
 * 且 dispose 不得在 stopChat 返回前同步发生（要留出中断落盘的宽限窗口）。
 * <p>
 * 项目原有测试不使用 Mockito，但此处需要 mock HarnessAgent/ReActAgent 才能观测
 * interrupt 调用，故引入 mockito-core（web 模块已随 spring-boot-starter-test 提供）。
 */
class StopChatMemoryPersistenceTest {

    private static final String WORKSPACE_ID = "ws-stop-1";
    private static final String SESSION_ID = "session-stop-1";

    /** 按发生顺序记录关键动作，用于断言 interrupt 与 dispose 的先后 */
    private final List<String> callOrder = new CopyOnWriteArrayList<>();

    private SessionRegistry sessions;
    private AgentService service;
    private RecordingDisposable disposable;

    /** 记录 dispose 时机的 Disposable */
    private final class RecordingDisposable implements Disposable {
        private final AtomicBoolean disposed = new AtomicBoolean(false);

        @Override
        public void dispose() {
            if (disposed.compareAndSet(false, true)) {
                callOrder.add("dispose");
            }
        }

        @Override
        public boolean isDisposed() {
            return disposed.get();
        }
    }

    @BeforeEach
    void setUp() {
        callOrder.clear();
        sessions = new SessionRegistry();

        ReActAgent delegate = mock(ReActAgent.class);
        doAnswer(inv -> {
            callOrder.add("interrupt");
            return null;
        }).when(delegate).interrupt(eq(AppConstants.DEFAULT_USER_ID), eq(SESSION_ID));

        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getDelegate()).thenReturn(delegate);

        WorkspaceContext ws = WorkspaceContext.builder()
                .workspaceId(WORKSPACE_ID)
                .userId(AppConstants.DEFAULT_USER_ID)
                .agent(agent)
                .build();

        WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
        when(workspaceManager.getWorkspace(WORKSPACE_ID)).thenReturn(ws);

        service = new AgentService(
                workspaceManager,
                mock(AgentFactory.class),
                mock(PermissionRuleService.class),
                new AgentScopeProperties(),
                sessions,
                mock(ScenarioResolver.class),
                new com.xinl.easyclaw.workspace.WorkspaceFileLayout());

        disposable = new RecordingDisposable();
        sessions.bindWorkspace(SESSION_ID, WORKSPACE_ID);
        sessions.setDisposable(SESSION_ID, disposable);
    }

    @Test
    @DisplayName("stopChat 不得在返回前同步 dispose——否则中断异常无从产生，记忆落盘被跳过")
    void stopChatMustNotDisposeSynchronously() {
        service.stopChat(WORKSPACE_ID, SESSION_ID);

        assertFalse(disposable.isDisposed(),
                "stopChat 同步 dispose 了订阅：Reactor cancel 不产生 InterruptedException，"
                        + "handleInterrupt 不会执行，停止前的提问与已执行内容不会写入 Agent 记忆");
    }

    @Test
    @DisplayName("interrupt 必须先于 dispose：中断标志要在订阅被取消前设好")
    void interruptMustPrecedeDispose() throws Exception {
        service.stopChat(WORKSPACE_ID, SESSION_ID);

        assertTrue(callOrder.contains("interrupt"), "未调用 agent.interrupt(userId, sessionId)");

        waitForDispose();

        int interruptIdx = callOrder.indexOf("interrupt");
        int disposeIdx = callOrder.indexOf("dispose");
        assertTrue(disposeIdx > interruptIdx,
                "dispose 早于或等于 interrupt（实际顺序 " + callOrder + "）："
                        + "订阅先被取消会让中断标志成为死信，记忆无法落盘");
    }

    @Test
    @DisplayName("宽限期后仍会兜底 dispose，订阅不泄漏")
    void disposeStillHappensAfterGraceWindow() throws Exception {
        service.stopChat(WORKSPACE_ID, SESSION_ID);

        waitForDispose();

        assertTrue(disposable.isDisposed(), "宽限期过后订阅仍未被 dispose，存在订阅泄漏风险");
        assertEquals(1, callOrder.stream().filter("dispose"::equals).count(),
                "dispose 被重复调用");
    }

    /** 等待宽限期内的兜底 dispose 发生（留足余量，避免 CI 抖动导致假红） */
    private void waitForDispose() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (disposable.isDisposed()) {
                return;
            }
            Thread.sleep(50);
        }
    }
}
