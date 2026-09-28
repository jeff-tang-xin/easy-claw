package com.xinl.easyclaw.hub.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI chat.completion.chunk 流聚合器：把 SSE 增量聚合成一条完整 chat.completion（日志留档用）。
 * 透传（openai）与转换（anthropic）两条流式路径产出的 chunk 统一喂给本聚合器。
 * 脏 chunk（非 JSON、缺 choices）静默跳过，不影响转发。
 */
public class OpenAiChunkAggregator {

    /** 聚合中的 tool_call：arguments 为增量拼接。 */
    private static final class ToolCallAcc {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private final ObjectMapper om;
    private String id;
    private String model;
    private long created;
    private final StringBuilder content = new StringBuilder();
    private final Map<Integer, ToolCallAcc> toolCalls = new LinkedHashMap<>();
    private String finishReason;
    private Integer promptTokens;
    private Integer completionTokens;

    public OpenAiChunkAggregator(ObjectMapper om) {
        this.om = om;
    }

    /** 喂一个 chunk 的 JSON（SSE data 行内容）；[DONE] 与非 JSON 直接忽略。 */
    public void onChunk(String dataJson) {
        if (dataJson == null || dataJson.isBlank() || "[DONE]".equals(dataJson.trim())) {
            return;
        }
        JsonNode chunk;
        try {
            chunk = om.readTree(dataJson);
        } catch (Exception e) {
            return;
        }
        if (id == null && chunk.hasNonNull("id")) {
            id = chunk.path("id").asText();
        }
        if (model == null && chunk.hasNonNull("model")) {
            model = chunk.path("model").asText();
        }
        if (created == 0 && chunk.path("created").isNumber()) {
            created = chunk.path("created").asLong();
        }
        for (JsonNode choice : chunk.path("choices")) {
            JsonNode delta = choice.path("delta");
            if (delta.hasNonNull("content")) {
                content.append(delta.path("content").asText());
            }
            for (JsonNode tc : delta.path("tool_calls")) {
                int index = tc.path("index").asInt(0);
                ToolCallAcc acc = toolCalls.computeIfAbsent(index, k -> new ToolCallAcc());
                if (tc.hasNonNull("id")) {
                    acc.id = tc.path("id").asText();
                }
                JsonNode fn = tc.path("function");
                if (fn.hasNonNull("name")) {
                    acc.name = fn.path("name").asText();
                }
                if (fn.hasNonNull("arguments")) {
                    acc.arguments.append(fn.path("arguments").asText());
                }
            }
            if (choice.hasNonNull("finish_reason")) {
                finishReason = choice.path("finish_reason").asText();
            }
        }
        JsonNode usage = chunk.path("usage");
        if (usage.isObject()) {
            if (usage.path("prompt_tokens").isNumber()) {
                promptTokens = usage.path("prompt_tokens").asInt();
            }
            if (usage.path("completion_tokens").isNumber()) {
                completionTokens = usage.path("completion_tokens").asInt();
            }
        }
    }

    /** 聚合结果：完整 chat.completion JSON（usage 未知则省略）。 */
    public ObjectNode aggregate() {
        ObjectNode out = om.createObjectNode();
        out.put("id", id != null ? id : "chatcmpl-unknown");
        out.put("object", "chat.completion");
        out.put("created", created);
        if (model != null) {
            out.put("model", model);
        }
        ArrayNode choices = out.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", content.toString());
        if (!toolCalls.isEmpty()) {
            ArrayNode tcs = message.putArray("tool_calls");
            toolCalls.forEach((index, acc) -> {
                ObjectNode tc = tcs.addObject();
                tc.put("id", acc.id != null ? acc.id : "");
                tc.put("type", "function");
                ObjectNode fn = tc.putObject("function");
                fn.put("name", acc.name != null ? acc.name : "");
                fn.put("arguments", acc.arguments.toString());
            });
        }
        choice.put("finish_reason", finishReason != null ? finishReason : "stop");
        if (promptTokens != null || completionTokens != null) {
            ObjectNode usage = out.putObject("usage");
            usage.put("prompt_tokens", promptTokens != null ? promptTokens : 0);
            usage.put("completion_tokens", completionTokens != null ? completionTokens : 0);
            usage.put("total_tokens", (promptTokens != null ? promptTokens : 0)
                    + (completionTokens != null ? completionTokens : 0));
        }
        return out;
    }

    public Integer promptTokens() {
        return promptTokens;
    }

    public Integer completionTokens() {
        return completionTokens;
    }
}
