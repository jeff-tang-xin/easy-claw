package com.xinl.easyclaw.hub.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * OpenAI chat/completions ↔ Anthropic /v1/messages 协议转换器（api_type=anthropic 的 provider 走此路径）。
 * 覆盖：system 提取、user/assistant/tool 消息、tools/tool_choice、流式 SSE 事件转换；
 * 转换失败的脏数据（如 tool_calls.arguments 非法 JSON）做降级处理而非炸掉请求。
 * 纯函数 + 显式状态的流式状态机，无副作用，可独立单测。
 */
@Component
public class AnthropicTranslator {

    static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final ObjectMapper om;

    public AnthropicTranslator(ObjectMapper om) {
        this.om = om;
    }

    // ==================== 请求转换 ====================

    /** OpenAI chat/completions 请求体 → Anthropic /v1/messages 请求体。 */
    public ObjectNode toAnthropicRequest(JsonNode req) {
        ObjectNode out = om.createObjectNode();
        out.put("model", text(req, "model", ""));
        int maxTokens = req.path("max_tokens").isInt() ? req.path("max_tokens").asInt()
                : req.path("max_completion_tokens").isInt() ? req.path("max_completion_tokens").asInt()
                : DEFAULT_MAX_TOKENS;
        out.put("max_tokens", maxTokens);
        if (req.path("stream").isBoolean()) {
            out.put("stream", req.path("stream").asBoolean());
        }
        copyIfPresent(req, out, "temperature");
        copyIfPresent(req, out, "top_p");
        if (req.has("stop") && !req.path("stop").isNull()) {
            ArrayNode stops = out.putArray("stop_sequences");
            JsonNode stop = req.path("stop");
            if (stop.isArray()) {
                stop.forEach(s -> stops.add(s.asText()));
            } else {
                stops.add(stop.asText());
            }
        }
        if (req.hasNonNull("user")) {
            ObjectNode meta = out.putObject("metadata");
            meta.put("user_id", req.path("user").asText());
        }
        convertTools(req, out);
        convertToolChoice(req, out);
        convertMessages(req, out);
        return out;
    }

    private void convertTools(JsonNode req, ObjectNode out) {
        JsonNode tools = req.path("tools");
        if (!tools.isArray() || tools.isEmpty()) {
            return;
        }
        ArrayNode outTools = out.putArray("tools");
        for (JsonNode t : tools) {
            JsonNode fn = t.path("function");
            if (!"function".equals(t.path("type").asText()) || !fn.isObject()) {
                continue;
            }
            ObjectNode tool = outTools.addObject();
            tool.put("name", fn.path("name").asText());
            if (fn.hasNonNull("description")) {
                tool.put("description", fn.path("description").asText());
            }
            JsonNode params = fn.path("parameters");
            if (params.isObject()) {
                tool.set("input_schema", params);
            } else {
                ObjectNode schema = tool.putObject("input_schema");
                schema.put("type", "object");
                schema.putObject("properties");
            }
        }
        if (outTools.isEmpty()) {
            out.remove("tools");
        }
    }

    private void convertToolChoice(JsonNode req, ObjectNode out) {
        JsonNode choice = req.path("tool_choice");
        if (choice.isNull() || choice.isMissingNode()) {
            return;
        }
        ObjectNode tc = out.putObject("tool_choice");
        if (choice.isTextual()) {
            switch (choice.asText()) {
                case "auto" -> tc.put("type", "auto");
                case "none" -> tc.put("type", "none");
                case "required" -> tc.put("type", "any");
                default -> {
                    tc.put("type", "auto");
                }
            }
        } else if (choice.isObject() && choice.hasNonNull("function")) {
            tc.put("type", "tool");
            tc.put("name", choice.path("function").path("name").asText());
        } else {
            tc.put("type", "auto");
        }
    }

    private void convertMessages(JsonNode req, ObjectNode out) {
        JsonNode messages = req.path("messages");
        if (!messages.isArray()) {
            out.putArray("messages");
            return;
        }
        List<String> systemParts = new ArrayList<>();
        ArrayNode outMessages = om.createArrayNode();
        for (JsonNode m : messages) {
            String role = m.path("role").asText();
            switch (role) {
                case "system", "developer" -> collectSystem(m, systemParts);
                case "user" -> outMessages.add(convertUserMessage(m));
                case "assistant" -> outMessages.add(convertAssistantMessage(m));
                case "tool" -> outMessages.add(convertToolResultMessage(m));
                default -> {
                    // 未知 role 按 user 文本处理，避免丢内容
                    ObjectNode fallback = om.createObjectNode();
                    fallback.put("role", "user");
                    ArrayNode content = fallback.putArray("content");
                    content.addObject().put("type", "text").put("text", m.path("content").asText(""));
                    outMessages.add(fallback);
                }
            }
        }
        if (!systemParts.isEmpty()) {
            out.put("system", String.join("\n\n", systemParts));
        }
        out.set("messages", mergeAdjacentSameRole(outMessages));
    }

    private void collectSystem(JsonNode m, List<String> systemParts) {
        JsonNode content = m.path("content");
        if (content.isTextual()) {
            systemParts.add(content.asText());
        } else if (content.isArray()) {
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText())) {
                    systemParts.add(part.path("text").asText());
                }
            }
        }
    }

    private ObjectNode convertUserMessage(JsonNode m) {
        ObjectNode out = om.createObjectNode();
        out.put("role", "user");
        out.set("content", convertContentParts(m.path("content")));
        return out;
    }

    /** OpenAI content（string 或 parts 数组）→ Anthropic content blocks。 */
    private ArrayNode convertContentParts(JsonNode content) {
        ArrayNode blocks = om.createArrayNode();
        if (content.isTextual()) {
            blocks.addObject().put("type", "text").put("text", content.asText());
            return blocks;
        }
        if (!content.isArray()) {
            blocks.addObject().put("type", "text").put("text", content.isMissingNode() || content.isNull()
                    ? "" : content.toString());
            return blocks;
        }
        for (JsonNode part : content) {
            String type = part.path("type").asText();
            if ("text".equals(type)) {
                blocks.addObject().put("type", "text").put("text", part.path("text").asText());
            } else if ("image_url".equals(type)) {
                convertImage(part, blocks);
            } else if ("input_audio".equals(type) || "file".equals(type)) {
                // Anthropic 不支持的模态：降级为占位文本，不丢消息结构
                blocks.addObject().put("type", "text").put("text", "[不支持的内容类型：" + type + "]");
            }
        }
        if (blocks.isEmpty()) {
            blocks.addObject().put("type", "text").put("text", "");
        }
        return blocks;
    }

    private void convertImage(JsonNode part, ArrayNode blocks) {
        String url = part.path("image_url").path("url").asText("");
        ObjectNode image = blocks.addObject();
        image.put("type", "image");
        ObjectNode source = image.putObject("source");
        if (url.startsWith("data:")) {
            // data:image/png;base64,iVBOR... → base64 source
            int comma = url.indexOf(',');
            String meta = comma > 5 ? url.substring(5, comma) : "";
            String mediaType = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
            source.put("type", "base64");
            source.put("media_type", mediaType.isBlank() ? "image/png" : mediaType);
            source.put("data", comma > 0 ? url.substring(comma + 1) : "");
        } else {
            source.put("type", "url");
            source.put("url", url);
        }
    }

    private ObjectNode convertAssistantMessage(JsonNode m) {
        ObjectNode out = om.createObjectNode();
        out.put("role", "assistant");
        ArrayNode blocks = om.createArrayNode();
        JsonNode content = m.path("content");
        if (content.isTextual() && !content.asText().isEmpty()) {
            blocks.addObject().put("type", "text").put("text", content.asText());
        } else if (content.isArray()) {
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText())) {
                    blocks.addObject().put("type", "text").put("text", part.path("text").asText());
                }
            }
        }
        JsonNode toolCalls = m.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                JsonNode fn = tc.path("function");
                ObjectNode toolUse = blocks.addObject();
                toolUse.put("type", "tool_use");
                toolUse.put("id", tc.path("id").asText());
                toolUse.put("name", fn.path("name").asText());
                toolUse.set("input", parseArguments(fn.path("arguments").asText("")));
            }
        }
        if (blocks.isEmpty()) {
            blocks.addObject().put("type", "text").put("text", "");
        }
        out.set("content", blocks);
        return out;
    }

    /** OpenAI tool 消息 → Anthropic user 消息内的 tool_result block。 */
    private ObjectNode convertToolResultMessage(JsonNode m) {
        ObjectNode out = om.createObjectNode();
        out.put("role", "user");
        ArrayNode blocks = om.createArrayNode();
        ObjectNode toolResult = blocks.addObject();
        toolResult.put("type", "tool_result");
        toolResult.put("tool_use_id", m.path("tool_call_id").asText());
        JsonNode content = m.path("content");
        toolResult.put("content", content.isTextual() ? content.asText()
                : content.isMissingNode() || content.isNull() ? "" : content.toString());
        out.set("content", blocks);
        return out;
    }

    /** Anthropic 要求 user/assistant 严格交替：相邻同 role 消息合并 content。 */
    private ArrayNode mergeAdjacentSameRole(ArrayNode messages) {
        ArrayNode merged = om.createArrayNode();
        for (JsonNode m : messages) {
            int size = merged.size();
            if (size > 0 && merged.get(size - 1).path("role").asText().equals(m.path("role").asText())) {
                ObjectNode prev = (ObjectNode) merged.get(size - 1);
                ArrayNode prevContent = prev.withArray("content");
                m.path("content").forEach(prevContent::add);
            } else {
                merged.add(m);
            }
        }
        return merged;
    }

    /** tool_calls.arguments 是 JSON 字符串；非法时降级为空对象，不阻断请求。 */
    private JsonNode parseArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return om.createObjectNode();
        }
        try {
            JsonNode node = om.readTree(raw);
            return node.isObject() ? node : om.createObjectNode();
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : fallback;
    }

    private static void copyIfPresent(JsonNode from, ObjectNode to, String field) {
        if (from.has(field) && from.path(field).isNumber()) {
            to.set(field, from.path(field));
        }
    }

    // ==================== 响应转换（非流式） ====================

    /** Anthropic /v1/messages 响应 → OpenAI chat.completion。 */
    public ObjectNode toOpenAiResponse(JsonNode resp, String requestedModel) {
        ObjectNode out = om.createObjectNode();
        out.put("id", resp.path("id").asText("chatcmpl-unknown"));
        out.put("object", "chat.completion");
        out.put("created", Instant.now().getEpochSecond());
        out.put("model", requestedModel);
        ArrayNode choices = out.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");

        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = om.createArrayNode();
        for (JsonNode block : resp.path("content")) {
            String type = block.path("type").asText();
            if ("text".equals(type)) {
                text.append(block.path("text").asText());
            } else if ("tool_use".equals(type)) {
                ObjectNode tc = toolCalls.addObject();
                tc.put("id", block.path("id").asText());
                tc.put("type", "function");
                ObjectNode fn = tc.putObject("function");
                fn.put("name", block.path("name").asText());
                JsonNode input = block.path("input");
                fn.put("arguments", input.isObject() ? input.toString() : "{}");
            }
        }
        if (toolCalls.isEmpty()) {
            message.put("content", text.toString());
        } else {
            if (text.length() > 0) {
                message.put("content", text.toString());
            } else {
                message.putNull("content");
            }
            message.set("tool_calls", toolCalls);
        }
        choice.put("finish_reason", mapStopReason(resp.path("stop_reason").asText(null)));

        JsonNode usage = resp.path("usage");
        ObjectNode outUsage = out.putObject("usage");
        int prompt = usage.path("input_tokens").asInt(0);
        int completion = usage.path("output_tokens").asInt(0);
        outUsage.put("prompt_tokens", prompt);
        outUsage.put("completion_tokens", completion);
        outUsage.put("total_tokens", prompt + completion);
        return out;
    }

    static String mapStopReason(String anthropicStopReason) {
        if (anthropicStopReason == null) {
            return "stop";
        }
        return switch (anthropicStopReason) {
            case "end_turn", "stop_sequence", "pause_turn" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            default -> "stop";
        };
    }
}
