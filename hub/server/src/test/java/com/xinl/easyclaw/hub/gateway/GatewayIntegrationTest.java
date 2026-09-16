package com.xinl.easyclaw.hub.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xinl.easyclaw.hub.contract.appkey.BindingRequest;
import com.xinl.easyclaw.hub.contract.appkey.CreateAppKeyRequest;
import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.provider.CreateProviderRequest;
import com.xinl.easyclaw.hub.contract.provider.UpdateProviderRequest;
import com.xinl.easyclaw.hub.repository.GatewayLogRepository;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LLM 网关端到端：appkey 认证（OpenAI 错误格式）→ model 路由 → 真实上游（内嵌 HttpServer）转发
 * （openai 透传注入真实 key / anthropic 双向转换）→ SSE 流式 → 异步详单落库 → 组织维度详单/用量查询。
 * 用户名统一 gw_ 前缀，slug 统一 gw- 前缀（slug 仅允许小写字母/数字/连字符，不能带下划线）；
 * 上游响应内容一律 ASCII（MockMvc 中文断言乱码）。
 */
class GatewayIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";
    private static final String REAL_OPENAI_KEY = "sk-real-openai-provider";
    private static final String REAL_ANTHROPIC_KEY = "sk-real-anthropic-provider";

    private static final String OPENAI_JSON = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-4o",
             "choices":[{"index":0,"message":{"role":"assistant","content":"hi there"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""";

    private static final String OPENAI_SSE = """
            data: {"id":"chatcmpl-s","object":"chat.completion.chunk","created":1,"model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

            data: {"id":"chatcmpl-s","object":"chat.completion.chunk","created":1,"model":"gpt-4o","choices":[{"index":0,"delta":{"content":"hello"},"finish_reason":null}]}

            data: {"id":"chatcmpl-s","object":"chat.completion.chunk","created":1,"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}

            data: [DONE]

            """;

    private static final String ANTHROPIC_JSON = """
            {"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"hi from claude"}],
             "model":"claude-x","stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":2}}""";

    private static final String ANTHROPIC_SSE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_s","type":"message","role":"assistant","content":[],"model":"claude-x","usage":{"input_tokens":6,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"stream hi"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    @Autowired
    private GatewayLogRepository gatewayLogRepository;

    private HttpServer upstream;
    private final AtomicReference<String> lastChatAuth = new AtomicReference<>();
    private final AtomicReference<String> lastChatBody = new AtomicReference<>();
    private final AtomicReference<String> lastMsgKey = new AtomicReference<>();
    private final AtomicReference<String> lastMsgVersion = new AtomicReference<>();
    private final AtomicReference<String> lastMsgBody = new AtomicReference<>();

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", ex -> {
            lastChatAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastChatBody.set(body);
            boolean stream = body.contains("\"stream\":true");
            respond(ex, stream ? OPENAI_SSE : OPENAI_JSON, stream ? "text/event-stream" : "application/json");
        });
        upstream.createContext("/v1/messages", ex -> {
            lastMsgKey.set(ex.getRequestHeaders().getFirst("x-api-key"));
            lastMsgVersion.set(ex.getRequestHeaders().getFirst("anthropic-version"));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastMsgBody.set(body);
            boolean stream = body.contains("\"stream\":true");
            respond(ex, stream ? ANTHROPIC_SSE : ANTHROPIC_JSON,
                    stream ? "text/event-stream" : "application/json");
        });
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    private static void respond(HttpExchange ex, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---------- 辅助 ----------

    private String upstreamBase() {
        return "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1";
    }

    private String newPlatformAdminToken(String username) throws Exception {
        createPlatformAdminOk(username, PW);
        return loginOk(username, PW).accessToken();
    }

    private static List<String> models(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).toList();
    }

    private long createProviderOk(String adminToken, String slug, String apiKey, String models, String apiType)
            throws Exception {
        String json = postJson("/api/providers",
                        new CreateProviderRequest(slug, "Name " + slug, upstreamBase(), apiKey,
                                models(models), null, null, apiType),
                        adminToken)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private long createOrgOk(String ownerToken, String name, String slug) throws Exception {
        String json = postJson("/api/orgs", new CreateOrgRequest(name, slug), ownerToken)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    /** 颁发 appkey 并断言 201，返回明文 key（modelName 传 "" 表示绑全部模型）。 */
    private String createAppKeyOk(String ownerToken, long orgId, long providerId, String modelName)
            throws Exception {
        String json = postJson("/api/orgs/" + orgId + "/appkeys",
                        new CreateAppKeyRequest("gw-key-" + providerId,
                                List.of(new BindingRequest(providerId, modelName))),
                        ownerToken)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("plainKey").asText();
    }

    private ResultActions gatewayChat(String body, String appKey) throws Exception {
        var builder = post("/api/gateway/v1/chat/completions");
        if (appKey != null) {
            builder = builder.header("Authorization", "Bearer " + appKey);
        }
        return mvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions gatewayModels(String appKey) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/gateway/v1/models")
                .header("Authorization", "Bearer " + appKey));
    }

    /** 异步详单写入器是单线程队列 + 共享上下文同库（跨用例残留）：按条件轮询等待目标条目（最长 3s）。 */
    private com.xinl.easyclaw.hub.entity.GatewayLogEntity awaitLog(
            java.util.function.Predicate<com.xinl.easyclaw.hub.entity.GatewayLogEntity> pred, String desc)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            var hit = gatewayLogRepository.findAll().stream().filter(pred).findFirst();
            if (hit.isPresent()) {
                return hit.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待详单超时：" + desc);
    }

    /** 一套可用环境的返回值。 */
    private record GwEnv(String plainKey, long orgId, long providerId) {
    }

    /** 搭建 openai provider（gpt-4o,gpt-4o-mini）+ org + 全模型绑定 key。 */
    private GwEnv setupOpenAiKey(String suffix) throws Exception {
        String admin = newPlatformAdminToken("gw_adm" + suffix.replace('-', '_'));
        long providerId = createProviderOk(admin, "gw-oai" + suffix, REAL_OPENAI_KEY, "gpt-4o,gpt-4o-mini", null);
        String owner = createUserAndLogin("gw_owner" + suffix.replace('-', '_'), PW);
        long orgId = createOrgOk(owner, "Gw Org" + suffix, "gw-org" + suffix);
        return new GwEnv(createAppKeyOk(owner, orgId, providerId, ""), orgId, providerId);
    }

    private static final String CHAT_REQ = """
            {"model":"gpt-4o","messages":[{"role":"user","content":"ping"}]}""";

    // ---------- 认证 ----------

    @Test
    void missingOrBadAppKey_returns401OpenAiErrorFormat() throws Exception {
        gatewayChat(CHAT_REQ, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"))
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
        gatewayChat(CHAT_REQ, "eck-doesnotexist000000000000000000")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    @Test
    void revokedKey_returns401() throws Exception {
        String admin = newPlatformAdminToken("gw_adm_rv");
        long providerId = createProviderOk(admin, "gw-rvp", REAL_OPENAI_KEY, "gpt-4o", null);
        String owner = createUserAndLogin("gw_owner_rv", PW);
        long orgId = createOrgOk(owner, "Gw OrgRv", "gw-org-rv");
        String plainKey = createAppKeyOk(owner, orgId, providerId, "");
        // 吊销后应 401
        String listJson = getJson("/api/orgs/" + orgId + "/appkeys", owner)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = om.readTree(listJson).get(0).get("id").asLong();
        postJson("/api/orgs/" + orgId + "/appkeys/" + id + "/revoke", null, owner)
                .andExpect(status().isNoContent());

        gatewayChat(CHAT_REQ, plainKey)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    // ---------- 路由 ----------

    @Test
    void modelNotBound_returns404AndLogsError() throws Exception {
        String key = setupOpenAiKey("-404").plainKey();
        gatewayChat("{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}", key)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("model_not_found"));
        var entry = awaitLog(l -> "gpt-5".equals(l.getModel()) && "error".equals(l.getStatus()),
                "gpt-5 错误详单");
        assertNull(entry.getApiType(), "未路由到 provider 时无 apiType");
        assertEquals(Integer.valueOf(404), entry.getHttpStatus());
    }

    @Test
    void disabledProvider_notRouted() throws Exception {
        String admin = newPlatformAdminToken("gw_adm_dis");
        long providerId = createProviderOk(admin, "gw-dis", REAL_OPENAI_KEY, "gpt-4o", null);
        String owner = createUserAndLogin("gw_owner_dis", PW);
        long orgId = createOrgOk(owner, "Gw OrgDis", "gw-org-dis");
        String key = createAppKeyOk(owner, orgId, providerId, "gpt-4o");
        // 禁用 provider（平台管理员 PATCH /api/providers/{id}）
        mvc.perform(MockMvcRequestBuilders.patch("/api/providers/" + providerId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new UpdateProviderRequest(null, null, null, null, "disabled", null, null))))
                .andExpect(status().isOk());
        gatewayChat(CHAT_REQ, key)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("model_not_found"));
    }

    // ---------- openai 透传 ----------

    @Test
    void openAiPassthrough_injectsRealKey_andLogsSuccess() throws Exception {
        String key = setupOpenAiKey("-oai").plainKey();
        gatewayChat(CHAT_REQ, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("chatcmpl-1"))
                .andExpect(jsonPath("$.choices[0].message.content").value("hi there"))
                .andExpect(jsonPath("$.usage.total_tokens").value(5));

        // 上游收到的是 provider 真实 key，而不是 appkey
        assertEquals("Bearer " + REAL_OPENAI_KEY, lastChatAuth.get());
        JsonNode upstreamReq = om.readTree(lastChatBody.get());
        assertEquals("gpt-4o", upstreamReq.get("model").asText());

        var entry = awaitLog(l -> key.substring(0, 12).equals(l.getKeyPrefix())
                && "success".equals(l.getStatus()), "openai 成功详单");
        assertEquals("openai", entry.getApiType());
        assertEquals(Integer.valueOf(200), entry.getHttpStatus());
        assertEquals(Integer.valueOf(3), entry.getPromptTokens());
        assertEquals(Integer.valueOf(2), entry.getCompletionTokens());
        assertEquals(key.substring(0, 12), entry.getKeyPrefix());
        assertTrue(entry.getRequestBody().contains("gpt-4o"));
        assertTrue(entry.getResponseBody().contains("hi there"));
    }

    @Test
    void openAiStreaming_passthroughSseAndAggregatedLog() throws Exception {
        String key = setupOpenAiKey("-oss").plainKey();
        MvcResult pending = gatewayChat(
                        "{\"model\":\"gpt-4o\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}",
                        key)
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("chat.completion.chunk"), "应透传上游 chunk，实际：" + body);
        assertTrue(body.contains("hello"));
        assertTrue(body.contains("[DONE]"));

        var entry = awaitLog(l -> key.substring(0, 12).equals(l.getKeyPrefix()) && l.isStream(),
                "openai 流式详单");
        assertEquals("success", entry.getStatus());
        assertTrue(entry.isStream());
        assertTrue(entry.getResponseBody().contains("hello"), "流式响应应聚合落档");
        assertEquals(Integer.valueOf(4), entry.getPromptTokens());
    }

    // ---------- anthropic 转换 ----------

    @Test
    void anthropicTranslated_requestConvertedAndResponseMappedBack() throws Exception {
        String admin = newPlatformAdminToken("gw_adm_ant");
        long providerId = createProviderOk(admin, "gw-ant", REAL_ANTHROPIC_KEY, "claude-x", "anthropic");
        String owner = createUserAndLogin("gw_owner_ant", PW);
        long orgId = createOrgOk(owner, "Gw OrgAnt", "gw-org-ant");
        String key = createAppKeyOk(owner, orgId, providerId, "");

        gatewayChat("""
                {"model":"claude-x","max_tokens":256,
                 "messages":[{"role":"system","content":"You are helpful"},
                             {"role":"user","content":"ping"}]}""", key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
                .andExpect(jsonPath("$.choices[0].message.content").value("hi from claude"))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                .andExpect(jsonPath("$.usage.prompt_tokens").value(5))
                .andExpect(jsonPath("$.usage.completion_tokens").value(2));

        // 上游收到 anthropic 原生协议：x-api-key 注入真实 key，system 提取、无 system 角色消息
        assertEquals(REAL_ANTHROPIC_KEY, lastMsgKey.get());
        assertEquals("2023-06-01", lastMsgVersion.get());
        JsonNode upstreamReq = om.readTree(lastMsgBody.get());
        assertEquals("claude-x", upstreamReq.get("model").asText());
        assertEquals("You are helpful", upstreamReq.get("system").asText());
        assertEquals(256, upstreamReq.get("max_tokens").asInt());
        assertEquals(1, upstreamReq.get("messages").size());
        assertEquals("user", upstreamReq.get("messages").get(0).get("role").asText());

        var entry = awaitLog(l -> "claude-x".equals(l.getModel()) && !l.isStream()
                && "success".equals(l.getStatus()), "anthropic 非流式详单");
        assertEquals("anthropic", entry.getApiType());
        assertEquals("gw-ant", entry.getProviderSlug());
        // 落档的是转换后的 openai 格式（统一格式便于检索）
        assertTrue(entry.getResponseBody().contains("chat.completion"));
        assertTrue(entry.getRequestBody().contains("\"system\""), "请求体应留客户端原始 openai 格式");
    }

    @Test
    void anthropicStreaming_eventsConvertedToOpenAiChunks() throws Exception {
        String admin = newPlatformAdminToken("gw_adm_as");
        long providerId = createProviderOk(admin, "gw-ants", REAL_ANTHROPIC_KEY, "claude-x", "anthropic");
        String owner = createUserAndLogin("gw_owner_as", PW);
        long orgId = createOrgOk(owner, "Gw OrgAs", "gw-org-as");
        String key = createAppKeyOk(owner, orgId, providerId, "");

        MvcResult pending = gatewayChat(
                        "{\"model\":\"claude-x\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}",
                        key)
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("chat.completion.chunk"), "anthropic 事件应转为 openai chunk，实际：" + body);
        assertTrue(body.contains("stream hi"));
        assertTrue(body.contains("\"finish_reason\":\"stop\""));
        assertFalse(body.contains("message_start"), "不得泄露 anthropic 原生事件名");
        assertTrue(body.contains("[DONE]"));

        var entry = awaitLog(l -> "claude-x".equals(l.getModel()) && l.isStream(),
                "anthropic 流式详单");
        assertEquals("success", entry.getStatus());
        assertTrue(entry.getResponseBody().contains("stream hi"));
        assertEquals(Integer.valueOf(6), entry.getPromptTokens());
        assertEquals(Integer.valueOf(3), entry.getCompletionTokens());
    }

    // ---------- models 列表 ----------

    @Test
    void modelsList_reflectsBindingsWithDedup() throws Exception {
        String admin = newPlatformAdminToken("gw_adm_m");
        long p1 = createProviderOk(admin, "gw-m1", REAL_OPENAI_KEY, "gpt-4o,gpt-4o-mini", null);
        long p2 = createProviderOk(admin, "gw-m2", REAL_ANTHROPIC_KEY, "gpt-4o,claude-x", "anthropic");
        String owner = createUserAndLogin("gw_owner_m", PW);
        long orgId = createOrgOk(owner, "Gw OrgM", "gw-org-m");
        String k1 = createAppKeyOk(owner, orgId, p1, "");
        String k2 = createAppKeyOk(owner, orgId, p2, "claude-x");

        gatewayModels(k1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[*].id",
                        containsInAnyOrder("gpt-4o", "gpt-4o-mini")));

        // 另一 key 只看到 claude-x；owned_by 为 provider slug
        gatewayModels(k2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value("claude-x"))
                .andExpect(jsonPath("$.data[0].owned_by").value("gw-m2"));

        gatewayModels("eck-bad-key")
                .andExpect(status().isUnauthorized());
    }

    // ---------- 详单/用量查询 ----------

    @Test
    void logQuery_ownerSeesDetail_memberForbidden_crossOrgHidden() throws Exception {
        GwEnv env = setupOpenAiKey("-lq");
        gatewayChat(CHAT_REQ, env.plainKey()).andExpect(status().isOk());
        long logId = awaitLog(l -> env.plainKey().substring(0, 12).equals(l.getKeyPrefix()),
                "logQuery 目标详单").getId();

        String owner = loginOk("gw_owner_lq", PW).accessToken();
        long orgId = env.orgId();

        getJson("/api/orgs/" + orgId + "/gateway-logs", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(logId))
                .andExpect(jsonPath("$.items[0].model").value("gpt-4o"))
                .andExpect(jsonPath("$.items[0].status").value("success"));

        // 详情带正文
        getJson("/api/orgs/" + orgId + "/gateway-logs/" + logId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestBody").isNotEmpty())
                .andExpect(jsonPath("$.responseBody").isNotEmpty());

        // member 无权
        createUserAndLogin("gw_member_lq", PW);
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("gw_member_lq", "member"), owner)
                .andExpect(status().isCreated());
        String memberToken = loginOk("gw_member_lq", PW).accessToken();
        getJson("/api/orgs/" + orgId + "/gateway-logs", memberToken)
                .andExpect(status().isForbidden());

        // 其它组织 owner 查不到（404 不泄露）
        String outsider = createUserAndLogin("gw_out_lq", PW);
        long otherOrg = createOrgOk(outsider, "Gw OrgOther", "gw-org-other");
        getJson("/api/orgs/" + otherOrg + "/gateway-logs/" + logId, outsider)
                .andExpect(status().isNotFound());
    }

    @Test
    void usage_aggregatesTotalsAndByModel() throws Exception {
        GwEnv env = setupOpenAiKey("-us");
        gatewayChat(CHAT_REQ, env.plainKey()).andExpect(status().isOk());
        gatewayChat("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}",
                env.plainKey())
                .andExpect(status().isOk());
        String prefix = env.plainKey().substring(0, 12);
        awaitLog(l -> prefix.equals(l.getKeyPrefix()) && "gpt-4o-mini".equals(l.getModel()),
                "usage 第二条详单");

        String owner = loginOk("gw_owner_us", PW).accessToken();
        long orgId = env.orgId();

        getJson("/api/orgs/" + orgId + "/gateway-logs/usage", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(2))
                .andExpect(jsonPath("$.successRequests").value(2))
                .andExpect(jsonPath("$.promptTokens").value(6))
                .andExpect(jsonPath("$.completionTokens").value(4))
                .andExpect(jsonPath("$.byModel", hasSize(greaterThanOrEqualTo(2))));
    }
}
