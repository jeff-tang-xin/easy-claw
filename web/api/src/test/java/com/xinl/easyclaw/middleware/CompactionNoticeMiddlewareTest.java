package com.xinl.easyclaw.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;

/**
 * Tests that CompactionNoticeMiddleware detects a freshly compacted context (summary message
 * id change across reasoning frames) and emits exactly one CustomEvent("compaction") per
 * compaction, while staying silent for pre-existing summaries (session restore / agent rebuild).
 */
class CompactionNoticeMiddlewareTest {

    private static Msg summaryMsg(String id) {
        return Msg.builder()
                .id(id)
                .name(ConversationCompactor.SUMMARY_MSG_NAME)
                .role(MsgRole.USER)
                .textContent("summary of earlier conversation")
                .build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder().name("user").role(MsgRole.USER).textContent(text).build();
    }

    private static AgentEventEmitter recordingEmitter(List<AgentEvent> captured) {
        return captured::add;
    }

    private static void runFrame(CompactionNoticeMiddleware mw, ReasoningInput input,
                                 AgentEventEmitter emitter) {
        Flux<AgentEvent> stream = mw.onReasoning(null, null, input, in -> Flux.empty());
        if (emitter != null) {
            stream = stream.contextWrite(Context.of(AgentEventEmitter.CONTEXT_KEY, emitter));
        }
        stream.blockLast();
    }

    @Test
    @DisplayName("首帧无摘要、次帧出现摘要 → 恰好通知一次，载荷带压缩后消息数")
    void notifiesOnceWhenSummaryAppears() {
        CompactionNoticeMiddleware mw = new CompactionNoticeMiddleware();
        List<AgentEvent> captured = new ArrayList<>();
        AgentEventEmitter emitter = recordingEmitter(captured);

        runFrame(mw, new ReasoningInput(List.of(userMsg("a"), userMsg("b")), List.of(), null),
                emitter);
        assertTrue(captured.isEmpty(), "无摘要帧不通知");

        runFrame(mw, new ReasoningInput(
                List.of(summaryMsg("sum-1"), userMsg("recent")), List.of(), null), emitter);
        assertEquals(1, captured.size(), "摘要出现应通知一次");
        CustomEvent ce = (CustomEvent) captured.get(0);
        assertEquals(CompactionNoticeMiddleware.EVENT_NAME, ce.getName());
        assertEquals(2, ce.getValue().get("keeping"), "keeping = 压缩后推理输入消息总数");
    }

    @Test
    @DisplayName("摘要持续存在多帧 → 不重复通知")
    void doesNotRepeatForSameSummary() {
        CompactionNoticeMiddleware mw = new CompactionNoticeMiddleware();
        List<AgentEvent> captured = new ArrayList<>();
        AgentEventEmitter emitter = recordingEmitter(captured);

        runFrame(mw, new ReasoningInput(List.of(userMsg("a")), List.of(), null), emitter);
        ReasoningInput compacted = new ReasoningInput(
                List.of(summaryMsg("sum-1"), userMsg("recent")), List.of(), null);
        runFrame(mw, compacted, emitter);
        runFrame(mw, compacted, emitter);
        runFrame(mw, compacted, emitter);
        assertEquals(1, captured.size(), "同一摘要只通知一次");
    }

    @Test
    @DisplayName("首帧即含摘要（会话恢复/rebuild 存量）→ 静默登记不通知")
    void silentForPreExistingSummary() {
        CompactionNoticeMiddleware mw = new CompactionNoticeMiddleware();
        List<AgentEvent> captured = new ArrayList<>();
        AgentEventEmitter emitter = recordingEmitter(captured);

        ReasoningInput compacted = new ReasoningInput(
                List.of(summaryMsg("sum-old"), userMsg("recent")), List.of(), null);
        runFrame(mw, compacted, emitter);
        runFrame(mw, compacted, emitter);
        assertTrue(captured.isEmpty(), "存量摘要属恢复现场，不应误报为「刚压缩」");
    }

    @Test
    @DisplayName("链式压缩（摘要 id 再变）→ 再次通知")
    void notifiesAgainOnChainedCompaction() {
        CompactionNoticeMiddleware mw = new CompactionNoticeMiddleware();
        List<AgentEvent> captured = new ArrayList<>();
        AgentEventEmitter emitter = recordingEmitter(captured);

        runFrame(mw, new ReasoningInput(List.of(userMsg("a")), List.of(), null), emitter);
        runFrame(mw, new ReasoningInput(List.of(summaryMsg("sum-1")), List.of(), null), emitter);
        runFrame(mw, new ReasoningInput(List.of(summaryMsg("sum-2")), List.of(), null), emitter);
        assertEquals(2, captured.size(), "每次新压缩都应通知");
    }

    @Test
    @DisplayName("无 AgentEventEmitter（非流式路径）→ 静默跳过且不影响推理流")
    void skipsWithoutEmitter() {
        CompactionNoticeMiddleware mw = new CompactionNoticeMiddleware();
        runFrame(mw, new ReasoningInput(List.of(userMsg("a")), List.of(), null), null);
        // 次帧出现摘要：无 emitter 时只登记不抛异常
        runFrame(mw, new ReasoningInput(List.of(summaryMsg("sum-1")), List.of(), null), null);
    }

    @Test
    @DisplayName("登记在无 emitter 时同样推进：emitter 恢复后不重报旧压缩")
    void registrationAdvancesEvenWithoutEmitter() {
        CompactionNoticeMiddleware mw = new CompactionNoticeMiddleware();
        List<AgentEvent> captured = new ArrayList<>();
        AgentEventEmitter emitter = recordingEmitter(captured);

        runFrame(mw, new ReasoningInput(List.of(userMsg("a")), List.of(), null), null);
        // 压缩发生在无 emitter 的路径：登记已推进
        runFrame(mw, new ReasoningInput(List.of(summaryMsg("sum-1")), List.of(), null), null);
        // emitter 恢复后同一摘要不应补报
        runFrame(mw, new ReasoningInput(List.of(summaryMsg("sum-1")), List.of(), null), emitter);
        assertTrue(captured.isEmpty(), "已登记的摘要不得补报");
    }
}
