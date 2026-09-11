package com.xinl.easyclaw.memory.flush;

import com.xinl.easyclaw.memory.settings.MemorySettingsEntity;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ScopedMemoryFlushService} 行为测试：游标推进/窗口限幅/失败重试/节流/压缩重置/异步提交。
 * 提取执行点经包私有构造注入捕获器，不触碰真实模型与落盘。
 */
class ScopedMemoryFlushServiceTest {

    private Agent agent;
    private AgentState state;
    private final AtomicReference<List<Msg>> captured = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean failNext;

    private ScopedMemoryFlushService service;

    @BeforeEach
    void setUp() {
        agent = mock(Agent.class);
        state = mock(AgentState.class);
        when(agent.getAgentState()).thenReturn(state);
        calls.set(0);
        captured.set(null);
        failNext = false;
        service = new ScopedMemoryFlushService((a, rc, m, payload) -> {
            if (failNext) {
                throw new RuntimeException("模拟提取失败");
            }
            calls.incrementAndGet();
            captured.set(payload);
        });
    }

    private static List<Msg> msgs(int n) {
        List<Msg> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder().text("m" + i).build())
                    .build());
        }
        return list;
    }

    private static MemorySettingsEntity settings(String mode, int window, int background) {
        return MemorySettingsEntity.builder()
                .flushMode(mode)
                .flushMinGapMinutes(30)
                .flushWindowMessages(window)
                .flushBackgroundMessages(background)
                .build();
    }

    private void runSync(String key, MemorySettingsEntity settings) {
        service.runFlushBlocking(key, agent, RuntimeContext.empty(), settings, null);
    }

    @Test
    void 增量未超窗全取游标推进到末尾() {
        when(state.getContext()).thenReturn(msgs(8));
        runSync("s1", settings("always", 10, 5));
        assertEquals(1, calls.get());
        assertEquals(8, captured.get().size());
        assertEquals(8, service.cursorOf("s1"));
    }

    @Test
    void 超窗分批提取游标逐批推进且带背景() {
        when(state.getContext()).thenReturn(msgs(25));
        MemorySettingsEntity settings = settings("always", 10, 5);

        runSync("s2", settings);
        assertEquals(10, service.cursorOf("s2"));
        assertEquals(10, captured.get().size(), "首批游标在 0、无背景，仅 10 条新消息");

        runSync("s2", settings);
        assertEquals(20, service.cursorOf("s2"));
        assertEquals(15, captured.get().size(), "第二批 = 10 条新 + 5 条背景");

        runSync("s2", settings);
        assertEquals(25, service.cursorOf("s2"), "末批不足窗口也取完");

        int before = calls.get();
        runSync("s2", settings);
        assertEquals(before, calls.get(), "游标追平后空增量不再提取");
    }

    @Test
    void 提取失败游标不推进下轮重试() {
        when(state.getContext()).thenReturn(msgs(6));
        MemorySettingsEntity settings = settings("always", 10, 5);

        failNext = true;
        runSync("s3", settings);
        assertEquals(0, calls.get());
        assertEquals(0, service.cursorOf("s3"), "失败游标不得推进");

        failNext = false;
        runSync("s3", settings);
        assertEquals(1, calls.get());
        assertEquals(6, service.cursorOf("s3"));
    }

    @Test
    void throttled模式间隔内不重复提取() {
        when(state.getContext()).thenReturn(msgs(6));
        MemorySettingsEntity settings = settings("throttled", 10, 5);

        runSync("s4", settings);
        assertEquals(1, calls.get());

        when(state.getContext()).thenReturn(msgs(9));
        runSync("s4", settings);
        assertEquals(1, calls.get(), "minGap 内第二次被节流");
    }

    @Test
    void 压缩后游标越界重置提取最后窗口() {
        when(state.getContext()).thenReturn(msgs(30));
        MemorySettingsEntity settings = settings("always", 10, 5);
        runSync("s5", settings);
        assertEquals(10, service.cursorOf("s5"));

        // 模拟压缩：上下文从 30 条缩到 8 条，游标 10 越界
        when(state.getContext()).thenReturn(msgs(8));
        runSync("s5", settings);
        assertEquals(2, calls.get(), "游标重置后提取现有 8 条");
        assertEquals(8, service.cursorOf("s5"));
    }

    @Test
    void 异步提交最终执行且不抛回调用方() throws InterruptedException {
        when(state.getContext()).thenReturn(msgs(5));
        CountDownLatch latch = new CountDownLatch(1);
        ScopedMemoryFlushService asyncService = new ScopedMemoryFlushService((a, rc, m, payload) -> {
            captured.set(payload);
            latch.countDown();
        });
        // submit 立即返回，不阻塞
        asyncService.submitScopedFlush("s6", agent, RuntimeContext.empty(),
                settings("always", 10, 5), null);
        assertTrue(latch.await(10, TimeUnit.SECONDS), "异步提取应在宽限内执行");
        assertEquals(5, captured.get().size());
        assertEquals(5, asyncService.cursorOf("s6"));
        asyncService.shutdown();
    }
}
