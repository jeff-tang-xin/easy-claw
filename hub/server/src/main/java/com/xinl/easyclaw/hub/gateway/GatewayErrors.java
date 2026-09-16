package com.xinl.easyclaw.hub.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * OpenAI 兼容错误体构造：{@code {"error": {"message", "type", "code"}}}。
 * 网关对外（含上游错误回包装）统一用该格式，保证 OpenAI SDK/vendored 客户端可解析。
 */
public final class GatewayErrors {

    private static final ObjectMapper OM = new ObjectMapper();

    private GatewayErrors() {
    }

    public static ObjectNode openAiError(String message, String type, String code) {
        ObjectNode error = OM.createObjectNode();
        error.put("message", message);
        error.put("type", type);
        error.put("code", code);
        ObjectNode root = OM.createObjectNode();
        root.set("error", error);
        return root;
    }
}
