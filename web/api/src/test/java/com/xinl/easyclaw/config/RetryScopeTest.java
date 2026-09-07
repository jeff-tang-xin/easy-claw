package com.xinl.easyclaw.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RetryScope} 的会话隔离语义测试。
 * <p>
 * 覆盖 code-review Finding 5 的核心回归点：abort 只能影响目标会话，不得波及其他会话。
 */
class RetryScopeTest {

    @BeforeEach
    void setUp() {
        RetryScope.reset();
        RetryScope.unbindCurrent();
    }

    @Test
    @DisplayName("abort 只影响目标会话，不波及其他会话")
    void abortIsolatesSessions() {
        RetryScope.abort("A");

        assertTrue(RetryScope.isAborted("A"));
        assertFalse(RetryScope.isAborted("B"), "stopChat(A) 不得中止 B 会话的重试");
    }

    @Test
    @DisplayName("clear 只清除目标会话，不抹掉其他会话的停止意图")
    void clearIsolatesSessions() {
        RetryScope.abort("A");
        RetryScope.abort("B");

        RetryScope.clear("B");

        assertTrue(RetryScope.isAborted("A"), "streamChat(B) 不得清掉 A 刚设置的停止意图");
        assertFalse(RetryScope.isAborted("B"));
    }

    @Test
    @DisplayName("abort 幂等，标记不重复累积")
    void abortIsIdempotent() {
        RetryScope.abort("A");
        RetryScope.abort("A");

        assertEquals(1, RetryScope.abortedCount());
    }

    @Test
    @DisplayName("null sessionId 不写入标记且判定为未中止")
    void nullSessionIdIsIgnored() {
        RetryScope.abort(null);

        assertEquals(0, RetryScope.abortedCount());
        assertFalse(RetryScope.isAborted(null));
        RetryScope.clear(null); // 不应抛异常
    }

    @Test
    @DisplayName("clear 未知会话是安全的空操作")
    void clearUnknownSessionIsNoop() {
        RetryScope.clear("never-existed");

        assertEquals(0, RetryScope.abortedCount());
    }

    @Test
    @DisplayName("bindCurrent/unbindCurrent 正确管理线程级会话标识")
    void bindAndUnbindCurrent() {
        assertEquals(null, RetryScope.currentSessionId());

        RetryScope.bindCurrent("A");
        assertEquals("A", RetryScope.currentSessionId());

        RetryScope.unbindCurrent();
        assertEquals(null, RetryScope.currentSessionId(),
                "未解绑会导致线程池复用时把标识串给别的会话");
    }

    @Test
    @DisplayName("不同线程的 current 标识互不干扰")
    void currentSessionIdIsThreadConfined() throws Exception {
        RetryScope.bindCurrent("main-session");
        String[] seen = new String[1];

        Thread t = new Thread(() -> seen[0] = RetryScope.currentSessionId());
        t.start();
        t.join();

        assertEquals(null, seen[0]);
        assertEquals("main-session", RetryScope.currentSessionId());
    }
}
