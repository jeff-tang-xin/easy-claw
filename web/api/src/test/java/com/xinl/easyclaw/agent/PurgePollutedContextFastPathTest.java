package com.xinl.easyclaw.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the fast-path optimization in {@code AgentService.purgePollutedContext}.
 *
 * <p>This method runs on the hot path of every user turn. Before the optimization it
 * always rebuilt a full {@code cleaned} list (copying every Msg and every content block)
 * and only then discovered that all three counters were zero and threw the copy away.
 * Real sessions reach several hundred KB of context, so that wasted allocation showed up
 * as a visible stall right after sending a message.
 *
 * <p>The optimization decides during the first scan whether any of the three pollution
 * kinds exist and returns early when none do. The risk is that the early-exit predicate
 * drifts out of sync with the real cleanup conditions, letting pollution slip through.
 * These tests cover exactly that: clean context must be left untouched, and each of the
 * three pollution kinds must still be cleaned.
 */
class PurgePollutedContextFastPathTest {

    /** Invokes the private method directly, avoiding a full Spring context for a pure function. */
    private boolean purge(AgentState state) throws Exception {
        Method m = AgentService.class.getDeclaredMethod(
                "purgePollutedContext", AgentState.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(newWithoutConstructor(), state, "test-session");
    }

    /**
     * Creates an instance bypassing the constructor.
     *
     * <p>{@code purgePollutedContext} only reads its {@code state} argument and never
     * touches instance fields, so none of AgentService's many collaborators are needed.
     * A serialization constructor yields an all-null shell, which is the lightest option.
     */
    private AgentService newWithoutConstructor() throws Exception {
        return (AgentService) sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(AgentService.class,
                        Object.class.getDeclaredConstructor())
                .newInstance();
    }

    private Msg user(String text) {
        return Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private Msg toolCall(String id, String name) {
        return Msg.builder().role(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder().id(id).name(name).build()).build();
    }

    private Msg toolResult(String id, String name, String text) {
        return Msg.builder().role(MsgRole.TOOL)
                .content(ToolResultBlock.of(id, name,
                        TextBlock.builder().text(text).build()))
                .build();
    }

    @Test
    @DisplayName("clean context takes the fast path and is left untouched")
    void cleanContextTakesFastPath() throws Exception {
        AgentState state = AgentState.builder().build();
        state.contextMutable().addAll(List.of(
                user("hello"),
                toolCall("call_1", "read_file"),
                toolResult("call_1", "read_file", "file content"),
                user("go on")));
        int before = state.getContext().size();

        assertFalse(purge(state), "clean context must return false (no state file rewrite)");
        assertEquals(before, state.getContext().size(), "fast path must not modify context");
    }

    @Test
    @DisplayName("dangling tool_call still gets a paired result")
    void danglingToolCallStillPaired() throws Exception {
        AgentState state = AgentState.builder().build();
        state.contextMutable().addAll(List.of(
                user("run a command"),
                toolCall("call_x", "execute")));

        assertTrue(purge(state), "dangling tool_call must return true");
        boolean paired = state.getContext().stream()
                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .anyMatch(b -> "call_x".equals(b.getId()));
        assertTrue(paired, "dangling tool_call must be paired, otherwise the OpenAI API rejects it");
    }

    @Test
    @DisplayName("orphan ToolResultBlock is still removed")
    void orphanToolResultStillRemoved() throws Exception {
        AgentState state = AgentState.builder().build();
        state.contextMutable().addAll(List.of(
                user("hello"),
                toolResult("ghost_1", "read_file", "unclaimed result")));

        assertTrue(purge(state), "orphan ToolResultBlock must return true");
        boolean stillThere = state.getContext().stream()
                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .anyMatch(b -> "ghost_1".equals(b.getId()));
        assertFalse(stillThere, "orphan ToolResultBlock must be removed");
    }

    @Test
    @DisplayName("blank user message is still removed (easiest kind for the fast path to miss)")
    void emptyUserMsgStillRemoved() throws Exception {
        AgentState state = AgentState.builder().build();
        state.contextMutable().addAll(List.of(
                user("normal message"),
                user("   ")));

        assertTrue(purge(state), "blank user message must return true");
        assertEquals(1, state.getContext().size(), "blank user message must be removed");
    }

    /**
     * Guards the stale-ASKING recovery path.
     *
     * <p>When the confirmation dialog is showing and the user reloads or leaves the page,
     * the paused state is persisted with the ToolUseBlock still in ASKING. The previous
     * implementation deleted that whole message, which produced orphan ToolResultBlocks
     * and forced {@code enablePendingToolRecovery(false)} to paper over the damage.
     *
     * <p>Now the block is kept and paired with a DENIED result instead. Two properties
     * must hold, and both are load-bearing: the assistant message survives (otherwise we
     * are back to manufacturing orphans), and the pending set is emptied (otherwise
     * ReActAgent throws "Agent is paused for human-in-the-loop confirmation" on the next
     * user message).
     */
    @Test
    @DisplayName("stale ASKING tool_call is paired as DENIED instead of deleting the message")
    void askingToolCallPairedAsDenied() throws Exception {
        AgentState state = AgentState.builder().build();
        state.contextMutable().addAll(List.of(
                user("delete that file"),
                Msg.builder().role(MsgRole.ASSISTANT)
                        .content(ToolUseBlock.builder().id("call_ask").name("execute")
                                .state(ToolCallState.ASKING).build())
                        .build()));

        assertTrue(purge(state), "stale ASKING must be treated as pollution");

        boolean declarationKept = state.getContext().stream()
                .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                .anyMatch(b -> "call_ask".equals(b.getId()));
        assertTrue(declarationKept,
                "the ToolUseBlock must be kept; deleting it is what created orphan results");

        ToolResultBlock paired = state.getContext().stream()
                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .filter(b -> "call_ask".equals(b.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(paired, "ASKING tool_call must be paired, or the agent stays paused");
        assertEquals(ToolResultState.DENIED, paired.getState(),
                "unconfirmed means denied, matching ReActAgent.applyConfirmResults");
    }

    @Test
    @DisplayName("interrupted (non-ASKING) tool_call keeps INTERRUPTED, not DENIED")
    void interruptedToolCallKeepsInterruptedState() throws Exception {
        AgentState state = AgentState.builder().build();
        state.contextMutable().addAll(List.of(
                user("run a command"),
                toolCall("call_run", "execute")));

        assertTrue(purge(state), "dangling tool_call must return true");

        ToolResultBlock paired = state.getContext().stream()
                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .filter(b -> "call_run".equals(b.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(paired, "dangling tool_call must be paired");
        assertEquals(ToolResultState.INTERRUPTED, paired.getState(),
                "a tool stopped mid-execution was interrupted, not denied by the user");
    }
}
