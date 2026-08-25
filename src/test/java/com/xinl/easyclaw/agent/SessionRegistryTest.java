package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.agent.domain.StreamEvent;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionRegistry} 单元测试。
 * <p>
 * 重点验证三个清理入口（endTurn / abortTurn / release）的**边界差异**——
 * 这正是原先状态散落在 AgentService 时最容易出错的地方：
 * 回合授权跨回合残留会绕过确认弹窗，会话驱逐漏清会造成内存泄漏。
 * <p>
 * 项目未引入 Mockito，Disposable 用手写 fake 代替。
 */
class SessionRegistryTest {

    private SessionRegistry registry;

    /** 手写 Disposable fake：记录是否被 dispose，避免引入 Mockito */
    private static final class FakeDisposable implements Disposable {
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
        registry = new SessionRegistry();
    }

    // ==================== 回合授权 ====================

    @Test
    @DisplayName("回合授权累加而非覆盖，且按工具名精确匹配")
    void allowForTurnAccumulates() {
        registry.allowForTurn("s1", List.of("write_file"));
        registry.allowForTurn("s1", List.of("execute"));

        Set<String> allowed = registry.turnAllowedTools("s1");
        assertEquals(2, allowed.size());
        assertTrue(registry.isAllowedThisTurn("s1", "write_file"));
        assertTrue(registry.isAllowedThisTurn("s1", "execute"));
        assertFalse(registry.isAllowedThisTurn("s1", "read_file"));
        assertFalse(registry.isAllowedThisTurn("other", "write_file"));
    }

    @Test
    @DisplayName("endTurn 只清回合授权，保留 workspace 绑定与活跃时间")
    void endTurnClearsOnlyTurnScopedState() {
        registry.bindWorkspace("s1", "w1");
        registry.allowForTurn("s1", List.of("write_file"));
        registry.touch("s1");

        registry.endTurn("s1");

        assertFalse(registry.isAllowedThisTurn("s1", "write_file"),
                "回合授权绝不能跨回合存活，否则写类工具不再弹窗确认");
        assertEquals("w1", registry.workspaceOf("s1"), "workspace 绑定需跨回合保留");
        assertEquals(1, registry.trackedSessionCount(), "活跃时间需保留，否则会被误判为空闲");
    }

    // ==================== 待确认工具与超时 ====================

    @Test
    @DisplayName("armPendingConfirm 设置截止时间；超时列表按 now 过滤")
    void armPendingConfirmSetsDeadline() {
        List<ToolUseBlock> tools = List.of();
        registry.armPendingConfirm("s1", tools, 10);

        assertTrue(registry.hasPendingConfirm("s1"));
        assertSame(tools, registry.peekPendingConfirm("s1"));
        // 10 分钟后才过期：以「当前时刻」为界不应命中
        assertTrue(registry.expiredConfirmations(System.currentTimeMillis()).isEmpty());
        // 以「远未来」为界应命中
        assertEquals(List.of("s1"),
                registry.expiredConfirmations(System.currentTimeMillis() + 3_600_000L));
    }

    @Test
    @DisplayName("confirmTimeoutMinutes<=0 表示不限时等待，不登记截止时间")
    void armPendingConfirmWithoutTimeout() {
        registry.armPendingConfirm("s1", List.of(), 0);

        assertTrue(registry.hasPendingConfirm("s1"), "待确认工具仍需登记");
        assertTrue(registry.expiredConfirmations(Long.MAX_VALUE).isEmpty(),
                "关闭倒计时后不应被超时清扫误杀");
    }

    @Test
    @DisplayName("takePendingConfirm 消费一次后即为空（防止确认被重复执行）")
    void takePendingConfirmConsumesOnce() {
        registry.armPendingConfirm("s1", List.of(), 10);

        assertNotNull(registry.takePendingConfirm("s1"));
        assertNull(registry.takePendingConfirm("s1"));
        assertFalse(registry.hasPendingConfirm("s1"));
    }

    @Test
    @DisplayName("挂起回调只能被取走一次，且 disarm 后不再可取")
    void pendingCallbacksAreSingleUse() {
        AtomicInteger finished = new AtomicInteger();
        registry.registerPendingCallbacks("s1", finished::incrementAndGet, e -> { });

        Runnable finisher = registry.takePendingFinisher("s1");
        assertNotNull(finisher);
        finisher.run();
        assertEquals(1, finished.get());
        assertNull(registry.takePendingFinisher("s1"), "重复取回会导致重复收尾");

        registry.registerPendingCallbacks("s2", () -> { }, e -> { });
        registry.disarmConfirmTimeout("s2");
        assertNull(registry.takePendingFinisher("s2"));
        assertNull(registry.takePendingEmitter("s2"));
    }

    // ==================== 护栏计数 ====================

    @Test
    @DisplayName("工具失败计数按 (session, tool) 独立累加，成功后清零体现「连续」语义")
    void toolFailureCountsAreConsecutivePerTool() {
        assertEquals(1, registry.recordToolFailure("s1", "execute"));
        assertEquals(2, registry.recordToolFailure("s1", "execute"));
        assertEquals(1, registry.recordToolFailure("s1", "write_file"), "不同工具计数互不影响");
        assertEquals(1, registry.recordToolFailure("s2", "execute"), "不同会话计数互不影响");

        registry.resetToolFailure("s1", "execute");
        assertEquals(1, registry.recordToolFailure("s1", "execute"),
                "成功后必须清零，否则跨越成功的两次失败会被误判为连续失败");
        assertEquals(2, registry.recordToolFailure("s1", "write_file"), "重置不应牵连其他工具");
    }

    @Test
    @DisplayName("resetToolFailure 对未登记会话安全（无 NPE）")
    void resetToolFailureOnUnknownSession() {
        registry.resetToolFailure("never-seen", "execute");
        assertEquals(1, registry.recordToolFailure("never-seen", "execute"));
    }

    @Test
    @DisplayName("beginTurn 归零护栏计数与恢复标记，否则长会话第 N 轮会被误判为循环调度")
    void beginTurnResetsGuardCounters() {
        registry.recordSubagentCall("s1", "coder");
        registry.recordSubagentCall("s1", "coder");
        registry.recordToolFailure("s1", "execute");
        registry.markResuming("s1");

        registry.beginTurn("s1");

        assertEquals(1, registry.recordSubagentCall("s1", "coder"));
        assertEquals(1, registry.recordToolFailure("s1", "execute"));
        assertFalse(registry.isResuming("s1"));
    }

    // ==================== 恢复标记 ====================

    @Test
    @DisplayName("恢复标记只能被消费一次（保证 end 事件恰好发送一次）")
    void resumingFlagConsumedOnce() {
        assertFalse(registry.isResuming("s1"));
        registry.markResuming("s1");

        assertTrue(registry.isResuming("s1"), "isResuming 是只读查询，不应消费标记");
        assertTrue(registry.isResuming("s1"));
        assertTrue(registry.consumeResumingFlag("s1"));
        assertFalse(registry.consumeResumingFlag("s1"), "重复消费会导致 end 被重复抑制");
        assertFalse(registry.isResuming("s1"));
    }

    // ==================== 订阅管理 ====================

    @Test
    @DisplayName("replaceDisposable 返回被顶替的旧订阅，保证同一会话只有一个活跃流")
    void replaceDisposableReturnsPrevious() {
        FakeDisposable first = new FakeDisposable();
        FakeDisposable second = new FakeDisposable();

        assertNull(registry.replaceDisposable("s1", first));
        assertSame(first, registry.replaceDisposable("s1", second),
                "调用方需拿到旧订阅才能 dispose，否则暂停流与恢复流会重复推送事件");
        assertTrue(registry.isRunning("s1"));

        second.dispose();
        assertFalse(registry.isRunning("s1"), "已 dispose 的订阅不算运行中");
    }

    @Test
    @DisplayName("clearDisposableIfSame 只清自己的句柄，不误删后继流")
    void clearDisposableIfSameIsConditional() {
        FakeDisposable oldOne = new FakeDisposable();
        FakeDisposable newOne = new FakeDisposable();
        registry.setDisposable("s1", newOne);

        registry.clearDisposableIfSame("s1", oldOne);
        assertTrue(registry.isRunning("s1"), "旧流收尾时不得摘掉新流的句柄");

        registry.clearDisposableIfSame("s1", newOne);
        assertFalse(registry.isRunning("s1"));
    }

    // ==================== abortTurn / release 边界 ====================

    @Test
    @DisplayName("abortTurn 交还订阅并清本轮状态，但保留 workspace 绑定供后续对话复用")
    void abortTurnClearsTurnStateKeepsBinding() {
        FakeDisposable disposable = new FakeDisposable();
        registry.bindWorkspace("s1", "w1");
        registry.setDisposable("s1", disposable);
        registry.allowForTurn("s1", List.of("write_file"));
        registry.armPendingConfirm("s1", List.of(), 10);
        registry.registerPendingCallbacks("s1", () -> { }, e -> { });
        registry.markResuming("s1");
        registry.recordToolFailure("s1", "execute");

        Disposable returned = registry.abortTurn("s1");

        assertSame(disposable, returned, "订阅须交还调用方 dispose，registry 不自行取消");
        assertFalse(registry.hasPendingConfirm("s1"));
        assertFalse(registry.isAllowedThisTurn("s1", "write_file"));
        assertFalse(registry.isResuming("s1"));
        assertEquals(1, registry.recordToolFailure("s1", "execute"), "失败计数应已归零");
        assertTrue(registry.expiredConfirmations(Long.MAX_VALUE).isEmpty(), "确认倒计时应已撤销");
        assertNull(registry.takePendingFinisher("s1"));
        assertEquals("w1", registry.workspaceOf("s1"), "停止本轮不等于结束会话");
    }

    @Test
    @DisplayName("release 清空全部会话状态，含 workspace 绑定与子 Agent 缓冲前缀条目")
    void releaseClearsEverything() {
        FakeDisposable disposable = new FakeDisposable();
        registry.bindWorkspace("s1", "w1");
        registry.setDisposable("s1", disposable);
        registry.allowForTurn("s1", List.of("write_file"));
        registry.armPendingConfirm("s1", List.of(), 10);
        registry.touch("s1");
        registry.appendSubagentResult("s1::coder", "partial");
        registry.appendSubagentResult("s2::coder", "keep-me");

        Disposable returned = registry.release("s1");

        assertSame(disposable, returned);
        assertNull(registry.workspaceOf("s1"),
                "workspace 绑定必须清除，否则复用 sessionId 会继承上一会话的上下文");
        assertFalse(registry.isAllowedThisTurn("s1", "write_file"),
                "授权残留会让新会话绕过确认弹窗——这是权限缺陷而非单纯内存泄漏");
        assertFalse(registry.hasPendingConfirm("s1"));
        assertEquals(0, registry.trackedSessionCount());
        assertEquals("", registry.takeSubagentResult("s1::coder"), "本会话缓冲应按前缀清理");
        assertEquals("keep-me", registry.takeSubagentResult("s2::coder"), "不得牵连其他会话");
    }

    @Test
    @DisplayName("会话空闲判定以 touch 时间为准；release 后不再出现在空闲列表")
    void idleSessionsTrackedByTouch() {
        registry.touch("s1");

        assertTrue(registry.sessionsIdleBefore(System.currentTimeMillis() - 60_000L).isEmpty(),
                "刚活跃的会话不应被判为空闲");
        assertEquals(List.of("s1"), registry.sessionsIdleBefore(Long.MAX_VALUE));

        registry.release("s1");
        assertTrue(registry.sessionsIdleBefore(Long.MAX_VALUE).isEmpty());
    }

    @Test
    @DisplayName("空白 sessionId 不进入活跃时间表，避免脏 key 长期占位")
    void touchIgnoresBlankSessionId() {
        registry.touch(null);
        registry.touch("  ");

        assertEquals(0, registry.trackedSessionCount());
    }

    // ==================== 子 Agent 缓冲 ====================

    @Test
    @DisplayName("子 Agent 结果缓冲按 key 累积，取出即清空")
    void subagentBufferAccumulatesAndDrains() {
        registry.appendSubagentResult("s1::coder", "hello ");
        registry.appendSubagentResult("s1::coder", "world");

        assertEquals("hello world", registry.takeSubagentResult("s1::coder"));
        assertEquals("", registry.takeSubagentResult("s1::coder"), "取出后应为空而非 null");
    }

    @Test
    @DisplayName("超长子 Agent 输出被截断并附标记，防止单条结果撑爆内存")
    void subagentBufferTruncatesOversizedOutput() {
        String chunk = "x".repeat(100_000);
        for (int i = 0; i < 5; i++) {
            registry.appendSubagentResult("s1::coder", chunk);
        }

        String result = registry.takeSubagentResult("s1::coder");
        assertTrue(result.contains("已截断"), "应附截断提示");
        assertTrue(result.length() < 250_000, "实际长度=" + result.length() + "，不应无界增长");
    }

    // ==================== workspace 反查 ====================

    @Test
    @DisplayName("sessionsOfWorkspace 只返回绑定到目标 workspace 的会话")
    void sessionsOfWorkspaceFiltersByBinding() {
        registry.bindWorkspace("s1", "w1");
        registry.bindWorkspace("s2", "w1");
        registry.bindWorkspace("s3", "w2");

        List<String> found = registry.sessionsOfWorkspace("w1");
        assertEquals(2, found.size());
        assertTrue(found.containsAll(List.of("s1", "s2")));
        assertTrue(registry.sessionsOfWorkspace("absent").isEmpty());
    }
}
