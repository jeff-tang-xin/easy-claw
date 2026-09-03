package com.xinl.easyclaw.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xinl.easyclaw.agent.SessionRegistry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Verifies the shadow-mode middlewares observe the acting stream without altering it.
 *
 * <p>The critical property under test is transparency: while the old implementations in
 * AgentService are still live, these middlewares must not add, drop, or reorder any event.
 * A regression here would mean duplicated file_changed pushes or a doubled failure counter.
 */
class ShadowMiddlewareTransparencyTest {

    private static ToolUseBlock toolCall(String id, String name, Map<String, Object> input) {
        return ToolUseBlock.builder().id(id).name(name).input(input).build();
    }

    private static ToolResultEndEvent result(String callId, String name, ToolResultState state) {
        return new ToolResultEndEvent("reply-1", callId, name, state);
    }

    @Test
    @DisplayName("FileChangeMiddleware passes the acting stream through unchanged")
    void fileChangeMiddlewareIsTransparent() {
        FileChangeMiddleware mw = new FileChangeMiddleware();
        ActingInput input = new ActingInput(List.of(
                toolCall("c1", "write_file", Map.of("path", "a\\b.txt"))));
        AgentEvent ev = result("c1", "write_file", ToolResultState.SUCCESS);

        List<AgentEvent> out = mw.onActing(null, RuntimeContext.builder().sessionId("s1").build(),
                        input, in -> Flux.just(ev))
                .collectList()
                .block();

        assertEquals(1, out.size(), "shadow middleware must not add or drop events");
        assertTrue(out.contains(ev), "the original event instance must be forwarded as-is");
    }

    @Test
    @DisplayName("ToolFailGuard does not touch SessionRegistry while in shadow mode")
    void toolFailGuardLeavesSharedCounterUntouched() {
        SessionRegistry registry = new SessionRegistry();
        ToolFailGuard guard = new ToolFailGuard(registry, 2);
        ActingInput input = new ActingInput(List.of(toolCall("c1", "read_file", Map.of())));

        // Two consecutive failures would trip the guard if it were active.
        for (int i = 0; i < 2; i++) {
            guard.onActing(null, RuntimeContext.builder().sessionId("s1").build(), input,
                            in -> Flux.just(result("c1", "read_file", ToolResultState.ERROR)))
                    .collectList()
                    .block();
        }

        // The shared counter must stay at zero: AgentService owns it during the shadow phase.
        // If the middleware also incremented it, the guard would fire at half the threshold.
        assertEquals(1, registry.recordToolFailure("s1", "read_file"),
                "shadow middleware must not write to the shared failure counter");
    }

    @Test
    @DisplayName("ToolFailGuard forwards every event untouched")
    void toolFailGuardIsTransparent() {
        ToolFailGuard guard = new ToolFailGuard(new SessionRegistry(), 2);
        ActingInput input = new ActingInput(List.of(toolCall("c1", "read_file", Map.of())));
        AgentEvent ev = result("c1", "read_file", ToolResultState.ERROR);

        List<AgentEvent> out = guard.onActing(null,
                        RuntimeContext.builder().sessionId("s1").build(), input, in -> Flux.just(ev))
                .collectList()
                .block();

        assertEquals(List.of(ev), out, "shadow middleware must be a pure pass-through");
    }
}
