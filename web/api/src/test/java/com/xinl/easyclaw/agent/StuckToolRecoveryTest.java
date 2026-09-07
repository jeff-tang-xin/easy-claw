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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺陷复现：工具执行期间后端假死，点「停止」也不往下进行。
 * <p>
 * 【根因链，均已由上游源码证实】
 * <ol>
 *   <li>工具执行被 {@code Mono.fromCallable(...).subscribeOn(boundedElastic)} 包裹，
 *       进入 {@code call()} 后即为不可打断的黑盒（如 {@code Process.waitFor}、
 *       管道 {@code InputStream.read}、GraalPy 原生执行对 {@code Thread.interrupt()} 免疫）。</li>
 *   <li>{@code toolExecutionConfig} 的超时是 {@code Mono.timeout}
 *       （ToolExecutor.java:426-429），只向下游发 error、向上游发 cancel，
 *       **不会真正放弃已阻塞的工作线程**；且当前配置为 30 分钟，对用户等同永久假死。</li>
 *   <li>{@code ReActAgent} 全文只有一个中断检查点（ReActAgent.java:1729），
 *       调用点在 {@code checkInterrupted().then(acting(iter))}（:2482，工具执行**之前**）
 *       与模型 chunk 上（:2549、:3601）。**工具执行内部没有任何检查点**
 *       ⇒ 中断标志最早只能在「本轮所有工具都返回后」被读到。工具不返回 ⇒ 永远读不到。</li>
 *   <li>于是 {@code AgentBase} 按 slotKey 串行化的 callGates 一直被占用，
 *       同一会话的下一条消息在 gate 上无声排队 —— 表现就是「停止了也不往下进行」。</li>
 * </ol>
 * <p>
 * 【本测试锁定的契约】{@code stopChat} 必须能从这种「中断打不进去」的状态中恢复：
 * 宽限期结束后若流仍未自行终止（证明中断没被读到、工具线程仍卡着），
 * 必须重建 Agent 以丢弃被占死的 callGates，让后续消息能正常处理；
 * 反之若流已在宽限期内自行终止（中断正常生效），则不得做无谓重建。
 */
class StuckToolRecoveryTest {

    private static final String WORKSPACE_ID = "ws-stuck-1";
    private static final String SESSION_ID = "session-stuck-1";

    /** 宽限期(2s) + 调度与断言余量，避免 CI 抖动假红 */
    private static final long RECOVER_WAIT_MS = 8_000L;

    private SessionRegistry sessions;
    private AgentService service;
    private WorkspaceManager workspaceManager;
    private StuckDisposable disposable;

    /**
     * 模拟「卡死的流」：dispose() 被调用也**不会**变成 isDisposed。
     * <p>
     * 这正是阻塞工具的真实行为 —— Reactor 的 cancel 无法终止已进入
     * {@code fromCallable} 的阻塞调用，上游线程继续占着 callGates。
     */
    private static final class StuckDisposable implements Disposable {
        private final AtomicBoolean disposeCalled = new AtomicBoolean(false);

        @Override
        public void dispose() {
            disposeCalled.set(true);
        }

        @Override
        public boolean isDisposed() {
            // 关键：始终为 false —— 流没有真正终止
            return false;
        }

        boolean disposeCalled() {
            return disposeCalled.get();
        }
    }

    /** 正常可取消的流：dispose 后立即终止（中断成功传播的情形） */
    private static final class NormalDisposable implements Disposable {
        private final AtomicBoolean disposed = new AtomicBoolean(false);

        @Override
        public void dispose() {
            disposed.set(true);
        }

        @Override
        public boolean isDisposed() {
            return disposed.get();
        }
    }

    @BeforeEach
    void setUp() {
        sessions = new SessionRegistry();

        ReActAgent delegate = mock(ReActAgent.class);
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getDelegate()).thenReturn(delegate);

        WorkspaceContext ws = WorkspaceContext.builder()
                .workspaceId(WORKSPACE_ID)
                .userId(AppConstants.DEFAULT_USER_ID)
                .agent(agent)
                .build();

        workspaceManager = mock(WorkspaceManager.class);
        when(workspaceManager.getWorkspace(WORKSPACE_ID)).thenReturn(ws);

        service = new AgentService(
                workspaceManager,
                mock(AgentFactory.class),
                mock(PermissionRuleService.class),
                new AgentScopeProperties(),
                sessions,
                mock(ScenarioResolver.class),
                new com.xinl.easyclaw.workspace.WorkspaceFileLayout());

        sessions.bindWorkspace(SESSION_ID, WORKSPACE_ID);
    }

    @Test
    @DisplayName("工具卡死时停止：宽限期后必须重建 Agent，否则 callGates 被占死、后续消息永久排队")
    void mustRebuildAgentWhenStreamCannotBeCancelled() {
        disposable = new StuckDisposable();
        sessions.setDisposable(SESSION_ID, disposable);

        service.stopChat(WORKSPACE_ID, SESSION_ID);

        verify(workspaceManager, timeout(RECOVER_WAIT_MS))
                .rebuildAgent(eq(WORKSPACE_ID));
    }

    @Test
    @DisplayName("工具卡死时停止：仍应尝试 dispose，订阅不泄漏")
    void mustStillAttemptDisposeWhenStuck() throws Exception {
        disposable = new StuckDisposable();
        sessions.setDisposable(SESSION_ID, disposable);

        service.stopChat(WORKSPACE_ID, SESSION_ID);

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECOVER_WAIT_MS);
        while (System.nanoTime() < deadline && !disposable.disposeCalled()) {
            Thread.sleep(50);
        }
        org.junit.jupiter.api.Assertions.assertTrue(disposable.disposeCalled(),
                "卡死场景下未调用 dispose，订阅会泄漏");
    }

    @Test
    @DisplayName("中断正常生效（流已自行终止）时不得重建 Agent——避免无谓丢弃热状态")
    void mustNotRebuildWhenInterruptWorked() throws Exception {
        NormalDisposable normal = new NormalDisposable();
        sessions.setDisposable(SESSION_ID, normal);

        service.stopChat(WORKSPACE_ID, SESSION_ID);
        // 模拟中断被 Agent 循环读到：流 error 终止，doFinally 已跑完
        normal.dispose();

        // 等过宽限窗口，确认兜底逻辑没有多余重建
        Thread.sleep(4_000L);
        verify(workspaceManager, never()).rebuildAgent(eq(WORKSPACE_ID));
    }
}
