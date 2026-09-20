package com.xinl.easyclaw.memory.flush;

import com.xinl.easyclaw.memory.settings.MemorySettingsEntity;
import com.xinl.easyclaw.memory.settings.MemorySettingsService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ScopedMemoryFlushMiddleware} 三态触发测试：异步（默认，主流零拖延）、
 * 同步（concatWith 等待）、never（不挂任何触发）。
 */
class ScopedMemoryFlushMiddlewareTest {

    private ScopedMemoryFlushService flushService;
    private MemorySettingsService settingsService;
    private ScopedMemoryFlushMiddleware middleware;
    private Agent agent;
    private AgentEvent event;

    @BeforeEach
    void setUp() {
        flushService = mock(ScopedMemoryFlushService.class);
        settingsService = mock(MemorySettingsService.class);
        middleware = new ScopedMemoryFlushMiddleware(flushService, settingsService, mock(Model.class));
        agent = mock(Agent.class);
        event = mock(AgentEvent.class);
    }

    private MemorySettingsEntity settings(String mode, boolean async) {
        return MemorySettingsEntity.builder()
                .flushMode(mode)
                .flushAsyncEnabled(async)
                .build();
    }

    @Test
    void never模式不挂任何触发主流原样() {
        when(settingsService.getOrCreate()).thenReturn(settings("never", true));

        Flux<AgentEvent> out = middleware.onAgent(agent, null, mock(AgentInput.class),
                in -> Flux.just(event));

        assertEquals(1, out.collectList().block().size());
        verifyNoInteractions(flushService);
    }

    @Test
    void 异步模式主流完成后提交提取且不阻塞事件流() {
        when(settingsService.getOrCreate()).thenReturn(settings("always", true));

        Flux<AgentEvent> out = middleware.onAgent(agent, null, mock(AgentInput.class),
                in -> Flux.just(event));

        // doOnComplete 在流终止时同步回调，block 返回前提交动作已发起
        assertEquals(1, out.collectList().block().size());
        verify(flushService).submitScopedFlush(any(), eq(agent), any(), any(), any());
    }

    @Test
    void 同步模式回合收尾前同步等待提取完成() {
        when(settingsService.getOrCreate()).thenReturn(settings("always", false));

        Flux<AgentEvent> out = middleware.onAgent(agent, null, mock(AgentInput.class),
                in -> Flux.just(event));

        // concatWith：collectList 返回 = 事件到达且 flush 已执行完毕
        assertEquals(1, out.collectList().block().size());
        verify(flushService).runFlushBlocking(any(), eq(agent), any(), any(), any());
    }
}
