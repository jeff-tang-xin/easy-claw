package com.xinl.easyclaw.hub.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xinl.easyclaw.hub.entity.AppKeyEntity;
import com.xinl.easyclaw.hub.entity.AppKeyProviderBindingEntity;
import com.xinl.easyclaw.hub.entity.LlmProviderEntity;
import com.xinl.easyclaw.hub.repository.AppKeyProviderBindingRepository;
import com.xinl.easyclaw.hub.repository.AppKeyRepository;
import com.xinl.easyclaw.hub.repository.LlmProviderRepository;
import com.xinl.easyclaw.hub.security.AppKeyContext;
import com.xinl.easyclaw.hub.service.CryptoService;
import com.xinl.easyclaw.hub.service.ProviderGrantService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** hub_cloud 路由在 GatewayService 主链路上的请求改写与协议分流。 */
class GatewayServiceCloudRouteTest {

    private static final Long APP_KEY_ID = 9L;
    private static final Long PROVIDER_ID = 7L;
    private static final String REAL_MODEL = "claude-real-model";

    private final AppKeyProviderBindingRepository bindings = mock(AppKeyProviderBindingRepository.class);
    private final AppKeyRepository appKeys = mock(AppKeyRepository.class);
    private final LlmProviderRepository providers = mock(LlmProviderRepository.class);
    private final ProviderGrantService providerGrantService = mock(ProviderGrantService.class);
    private final CryptoService cryptoService = mock(CryptoService.class);
    private final UpstreamClient upstream = mock(UpstreamClient.class);
    private final GatewayLogWriter logWriter = mock(GatewayLogWriter.class);
    private final ObjectMapper om = new ObjectMapper();
    private final AnthropicTranslator translator = new AnthropicTranslator(om);

    private GatewayService gateway;
    private AppKeyContext context;

    @BeforeEach
    void setUp() {
        gateway = new GatewayService(bindings, appKeys, providers, providerGrantService, cryptoService, upstream,
                translator, logWriter, om);
        context = new AppKeyContext(APP_KEY_ID, "cloud-key", "eck-test", 3L, 5L);

        AppKeyEntity appKey = new AppKeyEntity();
        appKey.setId(APP_KEY_ID);
        appKey.setOrgId(3L);
        appKey.setCloudProviderId(PROVIDER_ID);
        appKey.setCloudModelName(REAL_MODEL);

        AppKeyProviderBindingEntity binding = new AppKeyProviderBindingEntity();
        binding.setAppKeyId(APP_KEY_ID);
        binding.setProviderId(PROVIDER_ID);
        binding.setModelName("");

        when(appKeys.findById(APP_KEY_ID)).thenReturn(Optional.of(appKey));
        when(bindings.findByAppKeyId(APP_KEY_ID)).thenReturn(List.of(binding));
        when(cryptoService.decrypt("ciphertext")).thenReturn("plaintext-key");
    }

    @Test
    void hubCloudAnthropicRoute_replacesAliasBeforeProtocolTranslation() throws Exception {
        stubProvider("anthropic");
        String upstreamResponse = """
                {"id":"msg_1","type":"message","role":"assistant","content":[],"model":"%s",
                 "stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":2}}
                """.formatted(REAL_MODEL);
        when(upstream.post(any(), any(), any()))
                .thenReturn(new UpstreamClient.UpstreamFull(200,
                        upstreamResponse.getBytes(StandardCharsets.UTF_8)));

        ObjectNode request = om.createObjectNode();
        request.put("model", GatewayService.HUB_CLOUD_MODEL);
        request.putArray("messages").addObject().put("role", "user").put("content", "hello");
        ObjectNode result = chatAndExpectJson(request);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(upstream).post(url.capture(), headers.capture(), body.capture());

        assertTrue(url.getValue().endsWith("/v1/messages"));
        assertEquals("plaintext-key", headers.getValue().get("x-api-key"));
        JsonNode upstreamRequest = om.readTree(body.getValue());
        assertEquals(REAL_MODEL, upstreamRequest.path("model").asText());
        assertEquals(4096, upstreamRequest.path("max_tokens").asInt());
        assertEquals(REAL_MODEL, result.path("model").asText());
    }

    @Test
    void hubCloudOpenAiRoute_replacesAliasBeforePassthrough() throws Exception {
        stubProvider("openai");
        String upstreamResponse = """
                {"id":"chatcmpl-1","object":"chat.completion","model":"%s","choices":[]}
                """.formatted(REAL_MODEL);
        when(upstream.post(any(), any(), any()))
                .thenReturn(new UpstreamClient.UpstreamFull(200,
                        upstreamResponse.getBytes(StandardCharsets.UTF_8)));

        ObjectNode request = om.createObjectNode();
        request.put("model", GatewayService.HUB_CLOUD_MODEL);
        request.putArray("messages").addObject().put("role", "user").put("content", "hello");
        ObjectNode result = chatAndExpectJson(request);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(upstream).post(url.capture(), any(), body.capture());

        assertTrue(url.getValue().endsWith("/v1/chat/completions"));
        JsonNode upstreamRequest = om.readTree(body.getValue());
        assertEquals(REAL_MODEL, upstreamRequest.path("model").asText());
        assertEquals(REAL_MODEL, result.path("model").asText());
    }

    @Test
    void hubCloudRouteMissing_rejectsAliasAndDoesNotCallUpstream() throws Exception {
        AppKeyEntity appKey = new AppKeyEntity();
        appKey.setId(APP_KEY_ID);
        appKey.setOrgId(3L);
        when(appKeys.findById(APP_KEY_ID)).thenReturn(Optional.of(appKey));

        ObjectNode request = om.createObjectNode();
        request.put("model", GatewayService.HUB_CLOUD_MODEL);
        request.putArray("messages").addObject().put("role", "user").put("content", "hello");

        GatewayException ex = assertThrows(GatewayException.class,
                () -> gateway.chatCompletion(context, om.writeValueAsBytes(request), "127.0.0.1"));
        assertEquals(404, ex.status());
        assertEquals("cloud_route_not_configured", ex.code());
        verify(upstream, org.mockito.Mockito.never()).post(any(), any(), any());
    }

    private void stubProvider(String apiType) {
        LlmProviderEntity provider = new LlmProviderEntity();
        provider.setId(PROVIDER_ID);
        provider.setOrgId(3L);
        provider.setStatus("active");
        provider.setApiType(apiType);
        provider.setBaseUrl("http://127.0.0.1:1/v1");
        provider.setApiKeyCiphertext("ciphertext");
        provider.setModels(REAL_MODEL);
        when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));
    }

    private ObjectNode chatAndExpectJson(ObjectNode request) throws Exception {
        GatewayService.GatewayOutcome outcome =
                gateway.chatCompletion(context, om.writeValueAsBytes(request), "127.0.0.1");
        GatewayService.GatewayOutcome.Json json =
                assertInstanceOf(GatewayService.GatewayOutcome.Json.class, outcome);
        assertEquals(200, json.status());
        return json.body();
    }
}
