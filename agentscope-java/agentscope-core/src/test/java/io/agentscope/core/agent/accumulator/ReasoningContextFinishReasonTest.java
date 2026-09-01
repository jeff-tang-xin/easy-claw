/*
 * Copyright 2024 AgentScope Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agent.accumulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for finish_reason propagation.
 *
 * <p>The value was already parsed by the OpenAI response parser and stored on {@link
 * ChatResponse}, but nothing downstream ever read it, so a reply cut off by the output-token
 * ceiling reached the UI as a silently truncated message (text ending mid-word, unbalanced
 * markdown). These tests pin the accumulation semantics that let callers detect that case.
 */
class ReasoningContextFinishReasonTest {

    private static ChatResponse chunk(String text, String finishReason) {
        List<ContentBlock> blocks = List.of(TextBlock.builder().text(text).build());
        return ChatResponse.builder()
                .id("msg-1")
                .content(blocks)
                .finishReason(finishReason)
                .build();
    }

    @Test
    @DisplayName("finish_reason is null until the model reports one")
    void noFinishReasonByDefault() {
        ReasoningContext ctx = new ReasoningContext("agent");
        ctx.processChunk(chunk("hello", null));

        assertNull(ctx.getFinishReason());
        assertFalse(ctx.isTruncatedByLength());
    }

    @Test
    @DisplayName("streaming: terminal chunk's finish_reason survives earlier null chunks")
    void lastNonNullWins() {
        ReasoningContext ctx = new ReasoningContext("agent");
        // Typical stream: every chunk but the last carries finish_reason == null.
        ctx.processChunk(chunk("Apache ", null));
        ctx.processChunk(chunk("commons", null));
        ctx.processChunk(chunk("", "length"));

        assertEquals("length", ctx.getFinishReason());
        assertTrue(ctx.isTruncatedByLength());
    }

    @Test
    @DisplayName("later null chunks must not clobber an already-reported finish_reason")
    void nullDoesNotOverwrite() {
        ReasoningContext ctx = new ReasoningContext("agent");
        ctx.processChunk(chunk("done", "length"));
        // Some providers emit a trailing usage-only chunk with no finish_reason.
        ctx.processChunk(chunk("", null));

        assertEquals("length", ctx.getFinishReason());
        assertTrue(ctx.isTruncatedByLength());
    }

    @Test
    @DisplayName("normal completion is not flagged as truncated")
    void stopIsNotTruncation() {
        ReasoningContext ctx = new ReasoningContext("agent");
        ctx.processChunk(chunk("all good", "stop"));

        assertEquals("stop", ctx.getFinishReason());
        assertFalse(ctx.isTruncatedByLength());
    }
}
