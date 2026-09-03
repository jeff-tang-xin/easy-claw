package com.xinl.easyclaw.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xinl.easyclaw.agent.SessionRegistry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;

/**
 * Tests the consecutive-tool-failure guard after it took over from the old
 * AgentService implementation.
 *
 * <p>Two properties matter here. First, transparency: the middleware observes the
 * acting stream and must never add, drop, or reorder the events flowing through it —
 * self-emitted events go out through the AgentEventEmitter, not the returned Flux
 * (the framework discards that one at ReActAgent:2760). Second, the "consecutive"
 * semantics: a success must reset the counter, otherwise the guard degrades into a
 * cumulative-failure counter and eventually misfires on a merely flaky tool.
 */
class ToolFailGuardTest {

    private static final String SESSION = "s1";

    private static ToolUseBlock toolCall(String id, String name) {
        return ToolUseBlock.builder().id(id).name(name).input(Map.of()).build();
    }

    private static ToolResultEndEvent result(String callId, String name, ToolResultState state) {
        return new ToolResultEndEvent("reply-1", callId, name, state);
    }

    private static RuntimeContext ctx() {
        return RuntimeContext.builder().sessionId(SESSION).build();
    }

    private static AgentEventEmitter recordingEmitter(List<AgentEvent> captured) {
        return captured::add;
    }

    /** Runs one acting round returning the given tool result, capturing emitted events. */
    private static List<AgentEvent> runRound(ToolFailGuard guard, String tool,
                                             ToolResultState state, List<AgentEvent> captured) {
        ActingInput input = new ActingInput(List.of(toolCall("c1", tool)));
        AgentEvent ev = result("c1", tool, state);
        return guard.onActing(null, ctx(), input, in -> Flux.just(ev))
                .contextWrite(Context.of(AgentEventEmitter.CONTEXT_KEY,
                        recordingEmitter(captured)))
                .collectList()
                .block();
    }

    @Test
    @DisplayName("emits tool_fail_guard once the threshold is reached")
    void emitsGuardEventAtThreshold() {
        ToolFailGuard guard = new ToolFailGuard(new SessionRegistry(), 2);
        List<AgentEvent> captured = new ArrayList<>();

        runRound(guard, "read_file", ToolResultState.ERROR, captured);
        assertTrue(captured.isEmpty(), "first failure is below the threshold");

        runRound(guard, "read_file", ToolResultState.ERROR, captured);

        assertEquals(1, captured.size(), "second failure must trip the guard");
        CustomEvent ce = assertInstanceOf(CustomEvent.class, captured.get(0));
        assertEquals(ToolFailGuard.EVENT_NAME, ce.getName());
        assertEquals("read_file", ce.getValue().get("tool"));
        assertEquals(2, ce.getValue().get("fails"));
    }

    @Test
    @DisplayName("a success resets the counter, so failures must be consecutive")
    void successResetsCounter() {
        ToolFailGuard guard = new ToolFailGuard(new SessionRegistry(), 2);
        List<AgentEvent> captured = new ArrayList<>();

        runRound(guard, "read_file", ToolResultState.ERROR, captured);
        runRound(guard, "read_file", ToolResultState.SUCCESS, captured);
        runRound(guard, "read_file", ToolResultState.ERROR, captured);

        assertTrue(captured.isEmpty(),
                "the success in between breaks the streak; the guard must stay silent");
    }

    @Test
    @DisplayName("forwards every event untouched")
    void isTransparentToTheStream() {
        ToolFailGuard guard = new ToolFailGuard(new SessionRegistry(), 2);
        ActingInput input = new ActingInput(List.of(toolCall("c1", "read_file")));
        AgentEvent ev = result("c1", "read_file", ToolResultState.ERROR);

        List<AgentEvent> out = guard.onActing(null, ctx(), input, in -> Flux.just(ev))
                .contextWrite(Context.of(AgentEventEmitter.CONTEXT_KEY,
                        recordingEmitter(new ArrayList<>())))
                .collectList()
                .block();

        assertEquals(List.of(ev), out, "the acting stream must pass through unchanged");
    }

    @Test
    @DisplayName("threshold <= 0 disables the guard entirely")
    void disabledWhenThresholdNotPositive() {
        ToolFailGuard guard = new ToolFailGuard(new SessionRegistry(), 0);
        List<AgentEvent> captured = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            runRound(guard, "read_file", ToolResultState.ERROR, captured);
        }

        assertTrue(captured.isEmpty(), "a non-positive threshold turns the guard off");
    }

    @Test
    @DisplayName("counts each tool separately")
    void countsPerTool() {
        ToolFailGuard guard = new ToolFailGuard(new SessionRegistry(), 2);
        List<AgentEvent> captured = new ArrayList<>();

        runRound(guard, "read_file", ToolResultState.ERROR, captured);
        runRound(guard, "execute", ToolResultState.ERROR, captured);

        assertTrue(captured.isEmpty(),
                "one failure per tool must not add up across different tools");
    }

    @Test
    @DisplayName("survives a missing AgentEventEmitter (non-streaming call path)")
    void skipsEmissionWithoutEmitter() {
        ToolFailGuard guard = new ToolFailGuard(new SessionRegistry(), 1);
        ActingInput input = new ActingInput(List.of(toolCall("c1", "read_file")));
        AgentEvent ev = result("c1", "read_file", ToolResultState.ERROR);

        // No emitter in context: the guard still counts but must not blow up the stream.
        List<AgentEvent> out = guard.onActing(null, ctx(), input, in -> Flux.just(ev))
                .collectList()
                .block();

        assertEquals(List.of(ev), out, "the tool result must still reach the caller");
    }
}
