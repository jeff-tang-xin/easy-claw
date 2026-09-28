package com.xinl.easyclaw.hub.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic SSE 事件流 → OpenAI chat.completion.chunk 流（有状态，逐事件喂入）。
 * 每次 {@link #onEvent(String, JsonNode)} 返回 0~n 个 chunk JSON 字符串（调用方逐个写 SSE data 行）；
 * 流末尾的 {@code [DONE]} 由外层负责，不在此类。
 *
 * 事件映射：
 * - message_start → role chunk（记录 id/usage.input_tokens）
 * - content_block_start(tool_use) → tool_calls 首 chunk（id + name + 空 arguments）
 * - content_block_delta(text_delta) → content chunk
 * - content_block_delta(input_json_delta) → tool_calls arguments 增量 chunk
 * - message_delta(stop_reason) → finish chunk + usage
 * - content_block_stop / message_stop / ping → 无输出
 */
public class AnthropicStreamTranslator {

    private final ObjectMapper om;
    private final String requestedModel;
    private String messageId;
    private long created = Instant.now().getEpochSecond();
    private boolean roleSent;
    private int promptTokens;
    /** anthropic content_block index → openai tool_calls 序号。 */
    private final Map<Integer, Integer> toolCallIndexes = new HashMap<>();
    private int toolCallSeq;

    public AnthropicStreamTranslator(ObjectMapper om, String requestedModel) {
        this.om = om;
        this.requestedModel = requestedModel;
    }

    public List<String> onEvent(String event, JsonNode data) {
        List<String> out = new ArrayList<>();
        if (data == null || !data.isObject()) {
            return out;
        }
        switch (event) {
            case "message_start" -> {
                JsonNode message = data.path("message");
                messageId = message.path("id").asText("chatcmpl-unknown");
                promptTokens = message.path("usage").path("input_tokens").asInt(0);
                if (!roleSent) {
                    roleSent = true;
                    out.add(chunk(deltaWithRole(), null, null).toString());
                }
            }
            case "content_block_start" -> {
                JsonNode block = data.path("content_block");
                if ("tool_use".equals(block.path("type").asText())) {
                    int anthropicIndex = data.path("index").asInt(0);
                    int openAiIndex = toolCallSeq++;
                    toolCallIndexes.put(anthropicIndex, openAiIndex);
                    ObjectNode delta = om.createObjectNode();
                    ArrayNode toolCalls = delta.putArray("tool_calls");
                    ObjectNode tc = toolCalls.addObject();
                    tc.put("index", openAiIndex);
                    tc.put("id", block.path("id").asText());
                    tc.put("type", "function");
                    ObjectNode fn = tc.putObject("function");
                    fn.put("name", block.path("name").asText());
                    fn.put("arguments", "");
                    out.add(chunk(delta, null, null).toString());
                }
            }
            case "content_block_delta" -> {
                JsonNode deltaNode = data.path("delta");
                String deltaType = deltaNode.path("type").asText();
                if ("text_delta".equals(deltaType)) {
                    ObjectNode delta = om.createObjectNode();
                    delta.put("content", deltaNode.path("text").asText());
                    out.add(chunk(delta, null, null).toString());
                } else if ("input_json_delta".equals(deltaType)) {
                    int openAiIndex = toolCallIndexes.getOrDefault(data.path("index").asInt(0), 0);
                    ObjectNode delta = om.createObjectNode();
                    ArrayNode toolCalls = delta.putArray("tool_calls");
                    ObjectNode tc = toolCalls.addObject();
                    tc.put("index", openAiIndex);
                    tc.putObject("function").put("arguments", deltaNode.path("partial_json").asText());
                    out.add(chunk(delta, null, null).toString());
                }
            }
            case "message_delta" -> {
                String stopReason = data.path("delta").path("stop_reason").asText(null);
                int completionTokens = data.path("usage").path("output_tokens").asInt(0);
                ObjectNode usage = om.createObjectNode();
                usage.put("prompt_tokens", promptTokens);
                usage.put("completion_tokens", completionTokens);
                usage.put("total_tokens", promptTokens + completionTokens);
                out.add(chunk(om.createObjectNode(), AnthropicTranslator.mapStopReason(stopReason), usage)
                        .toString());
            }
            default -> {
                // content_block_stop / message_stop / ping：无对应 OpenAI chunk
            }
        }
        return out;
    }

    private ObjectNode deltaWithRole() {
        ObjectNode delta = om.createObjectNode();
        delta.put("role", "assistant");
        return delta;
    }

    private ObjectNode chunk(ObjectNode delta, String finishReason, ObjectNode usage) {
        ObjectNode chunk = om.createObjectNode();
        chunk.put("id", messageId != null ? messageId : "chatcmpl-unknown");
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", requestedModel);
        ArrayNode choices = chunk.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        choice.set("delta", delta);
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        } else {
            choice.putNull("finish_reason");
        }
        if (usage != null) {
            chunk.set("usage", usage);
        }
        return chunk;
    }
}
