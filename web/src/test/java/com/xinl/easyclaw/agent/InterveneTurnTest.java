package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.config.AppConstants;
import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.workspace.ScenarioResolver;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「用户主动介入轮次」的投递契约测试。
 * <p>
 * 缺陷背景：前端 interveneNow 原先只把消息重排到本地队列队首，不发任何后端请求，
 * 后端也没有 intervene 通道——点了介入等于什么都没发生。修复后介入消息必须真正
 * 落进 {@code agentscope:inbox:<sessionId>} 队列，才能被 InboxMiddleware 在
 * 下一个推理步之前排空并注入当前轮次。
 * <p>
 * 本测试锁定三件事：
 * <ol>
 *   <li>消息投到**正确的队列键**（键错则永远不会被该会话排空）</li>
 *   <li>payload 能被**真实的** {@code InboxMiddleware.deserializeHintBlock} 还原成 HintBlock
 *       （该方法只认 id/hint/source，且 hint 为空时静默丢弃——静默是本缺陷最危险的部分）</li>
 *   <li>非法入参与「工作区无 Agent」时不投递、不抛异常</li>
 * </ol>
 */
class InterveneTurnTest {

    private static final String WORKSPACE_ID = "ws-intervene-1";
    private static final String SESSION_ID = "session-intervene-1";

    private RecordingBus bus;
    private AgentService service;

    /** 记录 queuePush 的键与 payload，其余操作按最小可用实现 */
    private static final class RecordingBus implements MessageBus {
        private final List<String> pushedKeys = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> pushedPayloads = new CopyOnWriteArrayList<>();

        @Override
        public Mono<String> queuePush(String key, Map<String, Object> payload) {
            pushedKeys.add(key);
            pushedPayloads.add(new LinkedHashMap<>(payload));
            return Mono.just("entry-" + pushedKeys.size());
        }

        @Override
        public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
            List<BusEntry> out = new ArrayList<>();
            for (int i = 0; i < pushedPayloads.size() && out.size() < maxCount; i++) {
                if (pushedKeys.get(i).equals(key)) {
                    out.add(new BusEntry("entry-" + (i + 1), pushedPayloads.get(i)));
                }
            }
            return Mono.just(out);
        }

        @Override
        public Mono<Void> queueDelete(String key) {
            return Mono.empty();
        }

        @Override
        public Mono<Boolean> queuePeek(String key) {
            return Mono.just(pushedKeys.contains(key));
        }

        @Override
        public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
            return Mono.just("log-1");
        }

        @Override
        public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
            return Mono.just(List.of());
        }

        @Override
        public Mono<Void> logTrim(String key) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> publish(String key, Map<String, Object> payload) {
            return Mono.empty();
        }

        @Override
        public Flux<Map<String, Object>> subscribe(String key) {
            return Flux.empty();
        }
    }

    @BeforeEach
    void setUp() {
        bus = new RecordingBus();

        WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
        when(workspaceManager.getMessageBus(WORKSPACE_ID)).thenReturn(bus);

        service = new AgentService(
                workspaceManager,
                mock(AgentFactory.class),
                mock(PermissionRuleService.class),
                new AgentScopeProperties(),
                new SessionRegistry(),
                mock(ScenarioResolver.class),
                new com.xinl.easyclaw.workspace.WorkspaceFileLayout());
    }

    @Test
    @DisplayName("介入消息必须投进该会话的 inbox 队列键，否则永远不会被排空")
    void pushesToSessionInboxKey() {
        boolean ok = service.interveneTurn(WORKSPACE_ID, SESSION_ID, "改用 TypeScript 重写");

        assertTrue(ok, "interveneTurn 应返回 true 表示已投递");
        assertEquals(1, bus.pushedKeys.size(), "应恰好投递一条消息");
        assertEquals("agentscope:inbox:" + SESSION_ID, bus.pushedKeys.get(0),
                "队列键必须与 InboxMiddleware 排空时使用的键一致，否则消息永远不会进入当前轮次");
    }

    @Test
    @DisplayName("payload 键名必须匹配 InboxMiddleware 的 id/hint/source，且 hint 非空")
    void payloadMatchesHintBlockKeys() {
        String text = "改用 TypeScript 重写";
        service.interveneTurn(WORKSPACE_ID, SESSION_ID, text);

        List<BusEntry> drained = bus.queueDrain("agentscope:inbox:" + SESSION_ID, 100).block();
        assertNotNull(drained);
        assertEquals(1, drained.size(), "应能从 inbox 排空出这条介入消息");

        Map<String, Object> payload = drained.get(0).payload();

        // InboxMiddleware.deserializeHintBlock（:211-221）是 package-private，跨模块不可直接调用，
        // 故在此锁定它读取的三个键：hint 为 null 时该方法返回 null，消息会被**静默丢弃**
        // （无日志、无异常），是本缺陷最难排查的失败模式，因此必须显式断言。
        assertNotNull(payload.get("hint"), "缺少 hint 键：deserializeHintBlock 会返回 null，消息被静默丢弃");
        assertFalse(payload.get("hint").toString().isBlank(), "hint 不得为空白");
        assertTrue(payload.get("hint").toString().contains(text),
                "hint 未包含用户插话原文，实际=" + payload.get("hint"));
        assertNotNull(payload.get("id"), "缺少 id 键（缺失时中间件会自生成，但显式提供便于追踪）");
        assertEquals("user", payload.get("source"),
                "source 应标记为 user，便于模型区分插话来源");
    }

    @Test
    @DisplayName("空白文本不投递：避免推入 hint 为空的消息被中间件静默丢弃")
    void blankTextIsRejected() {
        assertFalse(service.interveneTurn(WORKSPACE_ID, SESSION_ID, "   "),
                "空白插话应直接拒绝");
        assertFalse(service.interveneTurn(WORKSPACE_ID, SESSION_ID, null),
                "null 插话应直接拒绝");
        assertTrue(bus.pushedKeys.isEmpty(), "非法入参不应产生任何投递");
    }

    @Test
    @DisplayName("工作区尚无 Agent（无 MessageBus）时安全返回 false，不抛异常")
    void missingBusReturnsFalse() {
        assertFalse(service.interveneTurn("ws-not-built", SESSION_ID, "在吗"),
                "无 MessageBus 时应返回 false 让上层能提示用户");
        assertTrue(bus.pushedKeys.isEmpty());
    }
}
