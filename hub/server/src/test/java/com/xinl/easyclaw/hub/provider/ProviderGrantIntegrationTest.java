package com.xinl.easyclaw.hub.provider;

import com.sun.net.httpserver.HttpServer;
import com.xinl.easyclaw.hub.contract.appkey.BindingRequest;
import com.xinl.easyclaw.hub.contract.appkey.CreateAppKeyRequest;
import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.provider.CreateProviderGrantRequest;
import com.xinl.easyclaw.hub.contract.provider.CreateProviderRequest;
import com.xinl.easyclaw.hub.entity.ProviderGrantEntity;
import com.xinl.easyclaw.hub.repository.ProviderGrantRepository;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Provider 授权（V26）：按用户授权 + 可选每日调用次数上限（NULL = 不限流）+ 可选有效期。
 * 启用语义：provider 无任何授权行 = 开放（存量回归）；有授权行 = 仅清单内用户可用。
 * 网关侧按 appkey 创建者（ctx.userId）校验：未授权 403 / 过期 403 / 超每日次数 429。
 * 上游用内置 HttpServer 模拟（同 GatewayIntegrationTest 模式）。
 */
class ProviderGrantIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";
    private static final String OPENAI_JSON =
            "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m1\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

    private HttpServer upstream;
    private final AtomicReference<String> lastChatAuth = new AtomicReference<>();

    @Autowired
    private ProviderGrantRepository grantRepository;

    @Autowired
    private com.xinl.easyclaw.hub.repository.GatewayLogRepository gatewayLogRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * 测试库由 Hibernate ddl-auto 建表，(grant_id, usage_date) 唯一约束在 SQLite 方言下未生成，
     * 而 upsert 的 ON CONFLICT 依赖它——这里显式补唯一索引（幂等；生产由 Flyway V26 建约束）。
     */
    @BeforeEach
    void ensureUsageUniqueIndex() {
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_grant_usage_grant_date "
                + "ON provider_grant_usage (grant_id, usage_date)");
    }

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", ex -> {
            lastChatAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = OPENAI_JSON.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
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

    private long createProviderOk(String adminToken, String slug) throws Exception {
        String json = postJson("/api/providers",
                        new CreateProviderRequest(slug, "Name " + slug, upstreamBase(), "sk-" + slug,
                                models("m1"), null, null, "openai"),
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

    /** appkey 创建结果：明文 key（调网关用）+ id（等详单落库用）。 */
    private record AppKeyRef(String plainKey, long id) {
    }

    private AppKeyRef createAppKeyOk(String token, long orgId, long providerId, String name) throws Exception {
        String json = postJson("/api/orgs/" + orgId + "/appkeys",
                        new CreateAppKeyRequest(name, List.of(new BindingRequest(providerId, "m1"))), token)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var node = om.readTree(json);
        return new AppKeyRef(node.get("plainKey").asText(), node.get("appKey").get("id").asLong());
    }

    /** 一套环境：平台管理员 + provider + org（owner/member 各一把绑定该 provider 的 key）。 */
    private record Env(String admin, long providerId, long orgId, AppKeyRef ownerKey, AppKeyRef memberKey) {
    }

    /**
     * 等待指定 appkey 的网关详单落库（最长 3s）。异步详单写入器是单线程队列 + 共享上下文同库：
     * 成功调用后必须等详单写完，否则异步写与下一用例的写库并发会触发 SQLite BUSY
     * （同 {@code GatewayIntegrationTest#awaitLog} 先例）。拒绝路径不写详单，无需等待。
     */
    private void awaitGatewayLog(long appKeyId, int expectedCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            long n = gatewayLogRepository.findAll().stream()
                    .filter(l -> l.getAppKeyId() != null && l.getAppKeyId() == appKeyId).count();
            if (n >= expectedCount) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待 appkey " + appKeyId + " 详单落库超时（期望 " + expectedCount + " 条）");
    }

    private Env setupEnv(String tag) throws Exception {
        String admin = newPlatformAdminToken("pg_adm_" + tag);
        long providerId = createProviderOk(admin, "pg-" + tag);
        String owner = createUserAndLogin("pg_owner_" + tag, PW);
        long orgId = createOrgOk(owner, "PG Org " + tag, "pg-" + tag);
        String member = createUserAndLogin("pg_member_" + tag, PW);
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("pg_member_" + tag, "member"), owner)
                .andExpect(status().isCreated());
        AppKeyRef ownerKey = createAppKeyOk(owner, orgId, providerId, "pg-owner-key");
        AppKeyRef memberKey = createAppKeyOk(member, orgId, providerId, "pg-member-key");
        return new Env(admin, providerId, orgId, ownerKey, memberKey);
    }

    private record GrantRef(long grantId) {
    }

    /** 平台管理员给指定用户配授权（默认不限流、永久），返回 grantId。 */
    private GrantRef grantOk(String adminToken, long providerId, long userId, Integer dailyLimit, Instant expiresAt)
            throws Exception {
        String json = postJson("/api/providers/" + providerId + "/grants",
                        new CreateProviderGrantRequest(userId, dailyLimit, expiresAt), adminToken)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new GrantRef(om.readTree(json).get("id").asLong());
    }

    private long userIdOf(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    // ---------- 管理端 ----------

    @Test
    void grant_crud_listWithUsernameAndUsedToday_duplicateConflict() throws Exception {
        Env env = setupEnv("crud");
        long ownerUserId = userIdOf("pg_owner_crud");

        // 配授权（每日 5 次）→ 201，带 username。
        String created = postJson("/api/providers/" + env.providerId() + "/grants",
                        new CreateProviderGrantRequest(ownerUserId, 5, null), env.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("pg_owner_crud"))
                .andExpect(jsonPath("$.dailyLimit").value(5))
                .andExpect(jsonPath("$.usedToday").value(0))
                .andReturn().getResponse().getContentAsString();
        long grantId = om.readTree(created).get("id").asLong();

        // 重复配同一用户 → 409。
        postJson("/api/providers/" + env.providerId() + "/grants",
                        new CreateProviderGrantRequest(ownerUserId, null, null), env.admin())
                .andExpect(status().isConflict());

        // 非法参数：dailyLimit=0 / 过去的 expiresAt → 400。
        postJson("/api/providers/" + env.providerId() + "/grants",
                        new CreateProviderGrantRequest(ownerUserId, 0, null), env.admin())
                .andExpect(status().isBadRequest());
        postJson("/api/providers/" + env.providerId() + "/grants",
                        new CreateProviderGrantRequest(ownerUserId, null, Instant.now().minusSeconds(3600)), env.admin())
                .andExpect(status().isBadRequest());

        // 列表可见。
        getJson("/api/providers/" + env.providerId() + "/grants", env.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(grantId));

        // 删除 → 204；再列表为空。
        deleteJson("/api/providers/" + env.providerId() + "/grants/" + grantId, env.admin())
                .andExpect(status().isNoContent());
        getJson("/api/providers/" + env.providerId() + "/grants", env.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void grant_manage_permission_platformPoolAdminOnly() throws Exception {
        Env env = setupEnv("perm");
        long ownerUserId = userIdOf("pg_owner_perm");

        // 平台池 provider：组织 owner 也不能管理授权 → 403。
        postJson("/api/providers/" + env.providerId() + "/grants",
                        new CreateProviderGrantRequest(ownerUserId, null, null),
                        createUserAndLogin("pg_other_perm", PW))
                .andExpect(status().isForbidden());
        // 非平台管理员的普通用户读授权列表 → 403。
        getJson("/api/providers/" + env.providerId() + "/grants", createUserAndLogin("pg_viewer_perm", PW))
                .andExpect(status().isForbidden());
    }

    // ---------- 网关校验 ----------

    @Test
    void gateway_noGrants_openByDefault() throws Exception {
        Env env = setupEnv("open");
        // provider 未配置任何授权行：owner 与 member 的 key 都可用（存量行为回归）。
        int ownerLogs = 0;
        int memberLogs = 0;
        for (AppKeyRef k : List.of(env.ownerKey(), env.memberKey())) {
            mvc.perform(MockMvcRequestBuilders.post("/api/gateway/v1/chat/completions")
                            .header("Authorization", "Bearer " + k.plainKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"m1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.choices[0].message.content").value("ok"));
            if (k == env.ownerKey()) {
                awaitGatewayLog(k.id(), ++ownerLogs);
            } else {
                awaitGatewayLog(k.id(), ++memberLogs);
            }
        }
    }

    @Test
    void gateway_authorizedPasses_unauthorized403() throws Exception {
        Env env = setupEnv("authz");
        // 仅授权 owner（member 未授权）→ 启用授权模式。
        grantOk(env.admin(), env.providerId(), userIdOf("pg_owner_authz"), null, null);

        // 被授权用户（owner 的 key）→ 200。
        mvc.perform(MockMvcRequestBuilders.post("/api/gateway/v1/chat/completions")
                        .header("Authorization", "Bearer " + env.ownerKey().plainKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"m1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk());
        awaitGatewayLog(env.ownerKey().id(), 1);

        // 未授权用户（member 的 key）→ 403 provider_not_authorized。
        mvc.perform(MockMvcRequestBuilders.post("/api/gateway/v1/chat/completions")
                        .header("Authorization", "Bearer " + env.memberKey().plainKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"m1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("provider_not_authorized"));
    }

    @Test
    void gateway_expiredGrant_403() throws Exception {
        Env env = setupEnv("exp");
        // 管理端拒绝配置过去的有效期（create 校验），过期态用 repository 直插构造。
        ProviderGrantEntity g = new ProviderGrantEntity();
        g.setProviderId(env.providerId());
        g.setUserId(userIdOf("pg_owner_exp"));
        g.setExpiresAt(Instant.now().minusSeconds(60));
        g.setCreatedBy(1L);
        grantRepository.save(g);

        mvc.perform(MockMvcRequestBuilders.post("/api/gateway/v1/chat/completions")
                        .header("Authorization", "Bearer " + env.ownerKey().plainKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"m1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("provider_grant_expired"));
    }

    @Test
    void gateway_dailyLimit_429OnExceed() throws Exception {
        Env env = setupEnv("limit");
        grantOk(env.admin(), env.providerId(), userIdOf("pg_owner_limit"), 2, null);

        String body = "{\"model\":\"m1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        // 第 1、2 次 → 200；第 3 次 → 429（限流口径 = 请求次数，成功也计数）。
        for (int i = 1; i <= 2; i++) {
            mvc.perform(MockMvcRequestBuilders.post("/api/gateway/v1/chat/completions")
                            .header("Authorization", "Bearer " + env.ownerKey().plainKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
            awaitGatewayLog(env.ownerKey().id(), i);
        }
        mvc.perform(MockMvcRequestBuilders.post("/api/gateway/v1/chat/completions")
                        .header("Authorization", "Bearer " + env.ownerKey().plainKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("provider_daily_limit_exceeded"));

        // member 无授权行 → 403（授权模式已启用），验证限流与授权闸门互不干扰。
        mvc.perform(MockMvcRequestBuilders.post("/api/gateway/v1/chat/completions")
                        .header("Authorization", "Bearer " + env.memberKey().plainKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("provider_not_authorized"));
    }
}
