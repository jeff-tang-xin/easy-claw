package com.xinl.easyclaw.hub.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class AnthropicTranslatorCloudRouteTest {

    private final AnthropicTranslator translator = new AnthropicTranslator(new ObjectMapper());

    @Test
    void missingMaxTokensDefaultsTo4096ForCloudAnthropicUpstream() throws Exception {
        ObjectNode request = new ObjectMapper().createObjectNode();
        request.put("model", "claude-sonnet-4-5-20250929");
        request.putArray("messages").addObject()
                .put("role", "user")
                .put("content", "ping");

        JsonNode translated = translator.toAnthropicRequest(request);

        assertEquals("claude-sonnet-4-5-20250929", translated.path("model").asText());
        assertEquals(4096, translated.path("max_tokens").asInt());
    }
}
