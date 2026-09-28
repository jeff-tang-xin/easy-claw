package com.xinl.easyclaw.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Tests that FileChangeMiddleware emits the correct CustomEvent("file_changed") via
 * AgentEventEmitter.
 *
 * <p>The middleware uses {@link Flux#deferContextual} to access the AgentEventEmitter from the
 * Reactor subscriber context. Tests must therefore {@code .contextWrite()} the emitter before
 * subscribing.
 */
class FileChangeMiddlewareTest {

    private static ToolUseBlock toolCall(String id, String name, Map<String, Object> input) {
        return ToolUseBlock.builder().id(id).name(name).input(input).build();
    }

    /**
     * Creates a fake AgentEventEmitter that records emitted events into the provided list.
     */
    private static AgentEventEmitter recordingEmitter(List<AgentEvent> captured) {
        return captured::add;
    }

    private static ToolResultEndEvent toolResult(String callId, String name,
                                                 ToolResultState state) {
        return new ToolResultEndEvent("reply-1", callId, name, state);
    }

    @Test
    @DisplayName("emits file_changed for write_file SUCCESS with path")
    void emitsFileChangedForWriteTool() {
        FileChangeMiddleware mw = new FileChangeMiddleware();
        ActingInput input = new ActingInput(List.of(
                toolCall("c1", "write_file", Map.of("path", "a/b.txt"))));
        AgentEvent result = toolResult("c1", "write_file", ToolResultState.SUCCESS);

        List<AgentEvent> captured = new ArrayList<>();
        AgentEventEmitter emitter = recordingEmitter(captured);

        List<AgentEvent> out = mw.onActing(null, null, input, in -> Flux.just(result))
                .contextWrite(Context.of(AgentEventEmitter.CONTEXT_KEY, emitter))
                .collectList()
                .block();

        assertEquals(1, out.size(), "middleware must not drop the original result event");
        assertTrue(out.contains(result), "original result must be forwarded as-is");

        assertEquals(1, captured.size(), "must emit one file_changed CustomEvent");
        AgentEvent emitted = captured.get(0);
        assertTrue(emitted instanceof CustomEvent, "must be a CustomEvent");
        CustomEvent ce = (CustomEvent) emitted;
        assertEquals(FileChangeMiddleware.EVENT_NAME, ce.getName());
        assertEquals("a/b.txt", ce.getValue().get("path"));
    }

    @Test
    @DisplayName("does not emit file_changed for tool ERROR")
    void notEmitOnError() {
        FileChangeMiddleware mw = new FileChangeMiddleware();
        ActingInput input = new ActingInput(List.of(
                toolCall("c1", "edit_file", Map.of("path", "x.txt"))));
        AgentEvent result = toolResult("c1", "edit_file", ToolResultState.ERROR);

        List<AgentEvent> captured = new ArrayList<>();
        AgentEventEmitter emitter = recordingEmitter(captured);

        mw.onActing(null, null, input, in -> Flux.just(result))
                .contextWrite(Context.of(AgentEventEmitter.CONTEXT_KEY, emitter))
                .blockLast();

        assertTrue(captured.isEmpty(), "no event on ERROR result");
    }

    @Test
    @DisplayName("emits empty path for execute (opaque write tool)")
    void emitsEmptyPathForOpaqueTool() {
        FileChangeMiddleware mw = new FileChangeMiddleware();
        ActingInput input = new ActingInput(List.of(
                toolCall("c1", "execute", Map.of("command", "git add ."))));
        AgentEvent result = toolResult("c1", "execute", ToolResultState.SUCCESS);

        List<AgentEvent> captured = new ArrayList<>();
        AgentEventEmitter emitter = recordingEmitter(captured);

        mw.onActing(null, null, input, in -> Flux.just(result))
                .contextWrite(Context.of(AgentEventEmitter.CONTEXT_KEY, emitter))
                .blockLast();

        assertEquals(1, captured.size(), "must emit for opaque tool");
        AgentEvent emitted = captured.get(0);
        assertTrue(emitted instanceof CustomEvent);
        CustomEvent ce = (CustomEvent) emitted;
        assertEquals("", ce.getValue().get("path"), "empty path signals unknown location");
    }

    @Test
    @DisplayName("skips emission when no AgentEventEmitter in context (non-streaming path)")
    void skipsWithoutEmitter() {
        FileChangeMiddleware mw = new FileChangeMiddleware();
        ActingInput input = new ActingInput(List.of(
                toolCall("c1", "write_file", Map.of("path", "x.txt"))));
        AgentEvent result = toolResult("c1", "write_file", ToolResultState.SUCCESS);

        // No emitter in context -- the middleware should just log and proceed
        List<AgentEvent> out = mw.onActing(null, null, input, in -> Flux.just(result))
                .collectList()
                .block();

        assertEquals(1, out.size(), "original stream must still pass through");
        assertTrue(out.contains(result));
    }

    @Test
    @DisplayName("does not emit for non-write tool results")
    void skipsForNonWriteTool() {
        FileChangeMiddleware mw = new FileChangeMiddleware();
        ActingInput input = new ActingInput(List.of(
                toolCall("c1", "read_file", Map.of("path", "x.txt"))));
        AgentEvent result = toolResult("c1", "read_file", ToolResultState.SUCCESS);

        List<AgentEvent> captured = new ArrayList<>();
        AgentEventEmitter emitter = recordingEmitter(captured);

        mw.onActing(null, null, input, in -> Flux.just(result))
                .contextWrite(Context.of(AgentEventEmitter.CONTEXT_KEY, emitter))
                .blockLast();

        assertTrue(captured.isEmpty(), "no event for read-only tool");
    }
}