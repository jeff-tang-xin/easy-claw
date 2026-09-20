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
 * {@link ScopedMemoryFlushService} 行为测试（方案 A：全量上下文载荷）：
 * 每次提取都喂入 state.getContext() 的完整快照、上下文增长后仍整段重扫、空上下文跳过、
 * 失败无游标副作用、throttled 节流、异步提交最终执行且不抛回调用方。
 * <p>
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

    private static MemorySettingsEntity settings(String mode) {
        return MemorySettingsEntity.builder()
                .flushMode(mode)
                .flushMinGapMinutes(30)
                .build();
    }

    private void runSync(String key, MemorySettingsEntity settings) {
        service.runFlushBlocking(key, agent, RuntimeContext.empty(), settings, null);
    }

    @Test
    void 每次提取都喂入完整上下文快照() {
        when(state.getContext()).thenReturn(msgs(8));
        runSync("s1", settings("always"));
        assertEquals(1, calls.get());
        assertEquals(8, captured.get().size());
    }

    @Test
    void 上下文增长后下轮仍全量提取而非增量() {
        when(state.getContext()).thenReturn(msgs(10));
        runSync("s2", settings("always"));
        assertEquals(10, captured.get().size());

        // 上下文从 10 增长到 25：全量语义应再次喂入全部 25 条，而非仅新增的 15 条
        when(state.getContext()).thenReturn(msgs(25));
        runSync("s2", settings("always"));
        assertEquals(2, calls.get());
        assertEquals(25, captured.get().size(), "全量模式每轮都重扫整段上下文");
    }

    @Test
    void 上下文为空时不发起提取() {
        when(state.getContext()).thenReturn(List.of());
        runSync("s3", settings("always"));
        assertEquals(0, calls.get());
    }

    @Test
    void 提取失败被吞掉且不影响后续回合提取() {
        when(state.getContext()).thenReturn(msgs(6));

        failNext = true;
        runSync("s4", settings("always"));
        assertEquals(0, calls.get());

        // 全量模式无游标，失败后下一轮照常整段重扫，无重试窗口状态残留
        failNext = false;
        runSync("s4", settings("always"));
        assertEquals(1, calls.get());
        assertEquals(6, captured.get().size());
    }

    @Test
    void throttled模式间隔内不重复提取() {
        when(state.getContext()).thenReturn(msgs(6));
        MemorySettingsEntity settings = settings("throttled");

        runSync("s5", settings);
        assertEquals(1, calls.get());

        when(state.getContext()).thenReturn(msgs(9));
        runSync("s5", settings);
        assertEquals(1, calls.get(), "minGap 内第二次被节流");
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
                settings("always"), null);
        assertTrue(latch.await(10, TimeUnit.SECONDS), "异步提取应在宽限内执行");
        assertEquals(5, captured.get().size());
        asyncService.shutdown();
    }
}
