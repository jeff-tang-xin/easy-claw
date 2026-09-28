package com.xinl.easyclaw.hub.provider;

import com.sun.net.httpserver.HttpServer;
import com.xinl.easyclaw.hub.contract.appkey.BindingRequest;
import com.xinl.easyclaw.hub.contract.appkey.CreateAppKeyRequest;
import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.provider.CreateProviderGrantRequest;
import com.xinl.easyclaw.hub.contract.provider.CreateProviderRequest;
import com.xinl.easyclaw.hub.contract.provider.UpdateGrantPlanRequest;
import com.xinl.easyclaw.hub.entity.ProviderCreditEntity;
import com.xinl.easyclaw.hub.entity.ProviderGrantEntity;
import com.xinl.easyclaw.hub.repository.ProviderCreditRepository;
import com.xinl.easyclaw.hub.repository.ProviderGrantRepository;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 积分体系（V27）集成测试：周期积分惰性发放（period_key 防重）、FIFO 按过期时间消耗、
 * 过期作废、模型目录比例扣减、小数 1 位舍弃（RoundingMode.DOWN）、与 daily_limit 独立叠加、
 * 余额不足 429、积分页面端点（我的余额/使用记录/组织与平台总览）。
 * 测试库由 Hibernate ddl-auto 建表，(grant_id, period_type, period_key) 唯一约束在 SQLite
 * 方言下未生成，而惰性发放的 ON CONFLICT 依赖它——显式补唯一索引（同 usage 表先例）。
 */
class ProviderCreditIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";
    private static final String OPENAI_JSON =
            "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m1\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

    private HttpServer upstream;

    @Autowired
    private ProviderGrantRepository grantRepository;

    @Autowired
    private ProviderCreditRepository creditRepository;

    @Autowired
    private com.xinl.easyclaw.hub.repository.GatewayLogRepository gatewayLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void ensureUniqueIndexes() {
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_grant_usage_grant_date "
                + "ON provider_grant_usage (grant_id, usage_date)");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_credits_grant_period "
                + "ON provider_credits (grant_id, period_type, period_key)");
    }

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", ex -> {
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

    private long createProviderOk(String adminToken, String slug, String modelsCsv) throws Exception {
        String json = postJson("/api/providers",
                        new CreateProviderRequest(slug, "Name " + slug, upstreamBase(), "sk-" + slug,
                                List.of(modelsCsv.split(",")), null, null, "openai"),
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

    private record AppKeyRef(String plainKey, long id) {
    }

    /** appkey 绑定 provider 全部模型（modelName=null），便于同一 key 打不同模型验证比例。 */
    private AppKeyRef createAppKeyOk(String token, long orgId, long providerId, String name) throws Exception {
        String json = postJson("/api/orgs/" + orgId + "/appkeys",
                        new CreateAppKeyRequest(name, List.of(new BindingRequest(providerId, null))), token)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var node = om.readTree(json);
        return new AppKeyRef(node.get("plainKey").asText(), node.get("appKey").get("id").asLong());
    }

    private record Env(String admin, long providerId, long orgId, AppKeyRef ownerKey, long ownerUserId) {
    }

    private Env setupEnv(String tag, String modelsCsv) throws Exception {
        String admin = newPlatformAdminToken("pc_adm_" + tag);
        long providerId = createProviderOk(admin, "pc-" + tag, modelsCsv);
        String owner = createUserAndLogin("pc_owner_" + tag, PW);
        long orgId = createOrgOk(owner, "PC Org " + tag, "pc-" + tag);
        AppKeyRef ownerKey = createAppKeyOk(owner, orgId, providerId, "pc-key-" + tag);
        return new Env(admin, providerId, orgId, ownerKey, userIdOf("pc_owner_" + tag));
    }

    private long userIdOf(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    private long grantOk(String adminToken, long providerId, long userId) throws Exception {
        String json = postJson("/api/providers/" + providerId + "/grants",
                        new CreateProviderGrantRequest(userId, null, null), adminToken)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    /** 配置周期积分发放计划（三项各自独立，null = 不发放）。 */
    private void planOk(String adminToken, long providerId, long grantId,
                        String daily, String monthly, String yearly) throws Exception {
        patchJson("/api/providers/" + providerId + "/grants/" + grantId + "/credit-plan",
                        new UpdateGrantPlanRequest(
                                daily == null ? null : new BigDecimal(daily),
                                monthly == null ? null : new BigDecimal(monthly),
                                yearly == null ? null : new BigDecimal(yearly)),
                        adminToken)
                .andExpect(status().isOk());
    }

    private void catalogOk(String adminToken, String modelName, String cost) throws Exception {
        postJson("/api/model-catalog",
                        om.readTree("{\"modelName\":\"" + modelName + "\",\"creditCost\":" + cost + "}"),
                        adminToken)
                .andExpect(status().isOk());
    }

    private void gatewayCall(AppKeyRef key, String model, int expectedStatus, String expectedCode) throws Exception {
        var result = MockMvcRequestBuilders.post("/api/gateway/v1/chat/completions")
                .header("Authorization", "Bearer " + key.plainKey())
                .contentType(MediaType_JSON)
                .content("{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        var assertion = mvc.perform(result).andExpect(status().is(expectedStatus));
        if (expectedCode != null) {
            assertion = assertion.andExpect(jsonPath("$.error.code").value(expectedCode));
        }
        if (expectedStatus == 200) {
            assertion.andExpect(jsonPath("$.choices[0].message.content").value("ok"));
        }
    }

    private static final org.springframework.http.MediaType MediaType_JSON =
            org.springframework.http.MediaType.APPLICATION_JSON;

    /** 等待指定 appkey 的网关详单落库（最长 3s），避免异步写与后续断言/用例并发触发 SQLite BUSY。 */
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

    /** 直插一笔积分行（构造过期/特定过期序等测试态）。 */
    private long insertCredit(long grantId, String periodType, String periodKey, String credits, Instant expiresAt) {
        ProviderCreditEntity c = new ProviderCreditEntity();
        c.setGrantId(grantId);
        c.setPeriodType(periodType);
        c.setPeriodKey(periodKey);
        c.setCredits(new BigDecimal(credits));
        c.setConsumed(BigDecimal.ZERO);
        c.setExpiresAt(expiresAt);
        return creditRepository.save(c).getId();
    }

    private BigDecimal remainingViaApi(String token) throws Exception {
        String json = getJson("/api/me/credits", token).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var arr = om.readTree(json);
        if (arr.isEmpty()) {
            throw new AssertionError("期望至少一行积分余额");
        }
        return new BigDecimal(arr.get(0).get("remaining").asText());
    }    // ---------- 管理端：计划与临时积分 ----------

    // 注意：model_catalog.model_name 全局唯一，且本测试类所有用例共享同一个 SQLite 库——
    // 各用例必须使用带 tag 的唯一模型名，避免目录比例相互污染（未登记模型默认 1 分/次）。

    @Test
    void plan_and_tempCredit_validation_and_ledger() throws Exception {
        Env env = setupEnv("adm", "m1");
        long grantId = grantOk(env.admin(), env.providerId(), env.ownerUserId());

        // 配计划：每日 10 / 每年 100 → 200，DTO 回带 remainingCredits=null（当期未发放）。
        patchJson("/api/providers/" + env.providerId() + "/grants/" + grantId + "/credit-plan",
                        new UpdateGrantPlanRequest(new BigDecimal("10"), null, new BigDecimal("100")), env.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyCredits").value(10))
                .andExpect(jsonPath("$.yearlyCredits").value(100))
                .andExpect(jsonPath("$.monthlyCredits").isEmpty());

        // 临时积分：面额 <0.1 → 400；过去有效期 → 400。
        postJson("/api/providers/" + env.providerId() + "/grants/" + grantId + "/credits",
                        om.readTree("{\"credits\":0.05,\"expiresAt\":\"" + Instant.now().plusSeconds(3600) + "\"}"),
                        env.admin())
                .andExpect(status().isBadRequest());
        postJson("/api/providers/" + env.providerId() + "/grants/" + grantId + "/credits",
                        om.readTree("{\"credits\":5,\"expiresAt\":\"" + Instant.now().minusSeconds(60) + "\"}"),
                        env.admin())
                .andExpect(status().isBadRequest());

        // 合法临时积分 → 201，periodType=temp。
        postJson("/api/providers/" + env.providerId() + "/grants/" + grantId + "/credits",
                        om.readTree("{\"credits\":5,\"expiresAt\":\"" + Instant.now().plusSeconds(3600) + "\"}"),
                        env.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periodType").value("temp"))
                .andExpect(jsonPath("$.credits").value(5));

        // 流水：1 行 temp。
        getJson("/api/providers/" + env.providerId() + "/grants/" + grantId + "/credits", env.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].periodType").value("temp"));
    }

    // ---------- 网关：惰性发放与防重 ----------

    @Test
    void gateway_dailyCredits_issuedOnce_perDay() throws Exception {
        Env env = setupEnv("issue", "m1-issue");
        long grantId = grantOk(env.admin(), env.providerId(), env.ownerUserId());
        planOk(env.admin(), env.providerId(), grantId, "10", null, null);
        // token 全程复用：429/详单异步写后避免再触发登录写 audit（SQLite 测试库写锁易撞 BUSY）。
        String ownerToken = loginOk("pc_owner_issue", PW).accessToken();

        // 首笔请求：惰性发放每日 10 并扣 1（未登记模型默认 1）→ 余额 9。
        gatewayCall(env.ownerKey(), "m1-issue", 200, null);
        awaitGatewayLog(env.ownerKey().id(), 1);
        org.assertj.core.api.Assertions.assertThat(remainingViaApi(ownerToken))
                .isEqualByComparingTo("9");

        // 同日第二笔：不重发（period_key 防重），再扣 1 → 余额 8。
        gatewayCall(env.ownerKey(), "m1-issue", 200, null);
        awaitGatewayLog(env.ownerKey().id(), 2);
        org.assertj.core.api.Assertions.assertThat(remainingViaApi(ownerToken))
                .isEqualByComparingTo("8");

        // 流水：仅 1 行 daily（面额 10）。
        getJson("/api/providers/" + env.providerId() + "/grants/" + grantId + "/credits", env.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].periodType").value("daily"))
                .andExpect(jsonPath("$[0].credits").value(10))
                .andExpect(jsonPath("$[0].consumed").value(2));
    }

    // ---------- 网关：FIFO 与过期作废 ----------

    @Test
    void gateway_fifo_byExpiry_and_expiredExcluded() throws Exception {
        Env env = setupEnv("fifo", "m1-fifo");
        long grantId = grantOk(env.admin(), env.providerId(), env.ownerUserId());
        // 每日积分 10（明天过期）；直插临时积分 5（1 小时后过期，比每日行先到期）。
        planOk(env.admin(), env.providerId(), grantId, "10", null, null);
        long tempRow = insertCredit(grantId, "temp", null, "5", Instant.now().plus(1, ChronoUnit.HOURS));
        String ownerToken = loginOk("pc_owner_fifo", PW).accessToken();

        // 第 1 笔请求扣临时行（先过期先耗）→ temp consumed=1，daily consumed=0。
        gatewayCall(env.ownerKey(), "m1-fifo", 200, null);
        awaitGatewayLog(env.ownerKey().id(), 1);
        ProviderCreditEntity temp = creditRepository.findById(tempRow).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(temp.getConsumed()).isEqualByComparingTo("1");

        // 直插一笔已过期临时积分 100 → 不计入余额（惰性过期）。
        insertCredit(grantId, "temp", null, "100", Instant.now().minusSeconds(60));
        BigDecimal remaining = remainingViaApi(ownerToken);
        // 余额 = temp 剩 4 + daily 10 = 14（过期 100 不计入）。
        org.assertj.core.api.Assertions.assertThat(remaining).isEqualByComparingTo("14");
    }

    // ---------- 网关：模型比例与小数舍弃 ----------

    @Test
    void gateway_modelCost_ratio_default1_and_truncation() throws Exception {
        Env env = setupEnv("ratio", "m1-ratio,m2-ratio,m9-ratio");
        long grantId = grantOk(env.admin(), env.providerId(), env.ownerUserId());
        planOk(env.admin(), env.providerId(), grantId, "5", null, null);
        catalogOk(env.admin(), "m1-ratio", "2.5");
        catalogOk(env.admin(), "m2-ratio", "0.5");
        String ownerToken = loginOk("pc_owner_ratio", PW).accessToken();

        // m1 → 扣 2.5 剩 2.5。
        gatewayCall(env.ownerKey(), "m1-ratio", 200, null);
        awaitGatewayLog(env.ownerKey().id(), 1);
        org.assertj.core.api.Assertions.assertThat(remainingViaApi(ownerToken))
                .isEqualByComparingTo("2.5");

        // m2 → 扣 0.5 剩 2.0。
        gatewayCall(env.ownerKey(), "m2-ratio", 200, null);
        awaitGatewayLog(env.ownerKey().id(), 2);
        org.assertj.core.api.Assertions.assertThat(remainingViaApi(ownerToken))
                .isEqualByComparingTo("2.0");

        // 未登记模型 m9 → 默认 1，剩 1.0。
        gatewayCall(env.ownerKey(), "m9-ratio", 200, null);
        awaitGatewayLog(env.ownerKey().id(), 3);
        org.assertj.core.api.Assertions.assertThat(remainingViaApi(ownerToken))
                .isEqualByComparingTo("1.0");

        // 使用记录：3 条，cost 依次 2.5 / 0.5 / 1（倒序）。
        getJson("/api/me/credit-usages", ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].modelName").value("m9-ratio"))
                .andExpect(jsonPath("$[0].cost").value(1))
                .andExpect(jsonPath("$[1].modelName").value("m2-ratio"))
                .andExpect(jsonPath("$[1].cost").value(0.5))
                .andExpect(jsonPath("$[2].modelName").value("m1-ratio"))
                .andExpect(jsonPath("$[2].cost").value(2.5));
    }

    @Test
    void gateway_fractionalCost_truncatesDown() throws Exception {
        Env env = setupEnv("frac", "m1-frac");
        long grantId = grantOk(env.admin(), env.providerId(), env.ownerUserId());
        planOk(env.admin(), env.providerId(), grantId, "1", null, null);
        catalogOk(env.admin(), "m1-frac", "0.3");
        String ownerToken = loginOk("pc_owner_frac", PW).accessToken();

        // 两次请求各扣 0.3 → 剩 0.4（1 位小数精确，无进位问题）。
        gatewayCall(env.ownerKey(), "m1-frac", 200, null);
        awaitGatewayLog(env.ownerKey().id(), 1);
        gatewayCall(env.ownerKey(), "m1-frac", 200, null);
        awaitGatewayLog(env.ownerKey().id(), 2);
        org.assertj.core.api.Assertions.assertThat(remainingViaApi(ownerToken))
                .isEqualByComparingTo("0.4");

        // 第三次：余额 0.4 ≥ 0.3 → 成功，剩 0.1。
        gatewayCall(env.ownerKey(), "m1-frac", 200, null);
        awaitGatewayLog(env.ownerKey().id(), 3);
        org.assertj.core.api.Assertions.assertThat(remainingViaApi(ownerToken))
                .isEqualByComparingTo("0.1");
    }

    // ---------- 网关：余额不足与 daily_limit 叠加 ----------

    @Test
    void gateway_insufficientCredits_429_partialConsumeKept() throws Exception {
        Env env = setupEnv("poor", "m1-poor");
        long grantId = grantOk(env.admin(), env.providerId(), env.ownerUserId());
        planOk(env.admin(), env.providerId(), grantId, "1", null, null);
        catalogOk(env.admin(), "m1-poor", "3");
        String ownerToken = loginOk("pc_owner_poor", PW).accessToken();

        // 余额 1 < 需 3 → 429 provider_credits_exhausted；已扣部分保留（失败请求也消耗）。
        gatewayCall(env.ownerKey(), "m1-poor", 429, "provider_credits_exhausted");
        org.assertj.core.api.Assertions.assertThat(remainingViaApi(ownerToken))
                .isEqualByComparingTo("0");
    }

    @Test
    void gateway_dailyLimit_stacksBeforeCredits() throws Exception {
        Env env = setupEnv("stack", "m1-stack");
        String created = postJson("/api/providers/" + env.providerId() + "/grants",
                        new CreateProviderGrantRequest(env.ownerUserId(), 1, null), env.admin())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long gid = om.readTree(created).get("id").asLong();
        planOk(env.admin(), env.providerId(), gid, "10", null, null);
        String ownerToken = loginOk("pc_owner_stack", PW).accessToken();

        // 第 1 次：次数与积分都够 → 200（发 10 扣 1，剩 9）。
        gatewayCall(env.ownerKey(), "m1-stack", 200, null);
        awaitGatewayLog(env.ownerKey().id(), 1);
        // 第 2 次：积分够（9）但次数超限 → 429 daily_limit（次数闸门先判，积分不动）。
        gatewayCall(env.ownerKey(), "m1-stack", 429, "provider_daily_limit_exceeded");
        org.assertj.core.api.Assertions.assertThat(remainingViaApi(ownerToken))
                .isEqualByComparingTo("9");
    }

    @Test
    void gateway_planAllNull_creditsNotEnabled_passesWithoutConsume() throws Exception {
        Env env = setupEnv("off", "m1-off");
        grantOk(env.admin(), env.providerId(), env.ownerUserId());
        String ownerToken = loginOk("pc_owner_off", PW).accessToken();
        // 计划全空且无积分行 → 积分池未启用，仅授权闸门生效。
        gatewayCall(env.ownerKey(), "m1-off", 200, null);
        awaitGatewayLog(env.ownerKey().id(), 1);
        // remaining = null（未启用）。
        String json = getJson("/api/me/credits", ownerToken)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(om.readTree(json).get(0).get("remaining").isNull()).isTrue();
    }

    // ---------- 积分页面端点：权限 ----------

    @Test
    void credits_page_endpoints_and_permissions() throws Exception {
        Env env = setupEnv("page", "m1-page");
        long grantId = grantOk(env.admin(), env.providerId(), env.ownerUserId());
        planOk(env.admin(), env.providerId(), grantId, "10", null, null);
        gatewayCall(env.ownerKey(), "m1-page", 200, null);
        awaitGatewayLog(env.ownerKey().id(), 1);

        String ownerToken = loginOk("pc_owner_page", PW).accessToken();

        // 我的余额：1 行，remaining=9，daily 构成 9。
        getJson("/api/me/credits", ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].remaining").value(9))
                .andExpect(jsonPath("$[0].dailyRemaining").value(9));

        // 组织总览：owner → 200；本用例 provider 是平台池（orgId=null），不在组织范围 → 空数组
        //（顺带验证组织总览的范围隔离：平台池不混入组织视图）。
        getJson("/api/orgs/" + env.orgId() + "/credit-overview", ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 组织总览：member（非 owner/admin）→ 403。
        createUserAndLogin("pc_member_page", PW);
        postJson("/api/orgs/" + env.orgId() + "/members", new AddMemberRequest("pc_member_page", "member"),
                        ownerToken)
                .andExpect(status().isCreated());
        getJson("/api/orgs/" + env.orgId() + "/credit-overview", loginOk("pc_member_page", PW).accessToken())
                .andExpect(status().isForbidden());

        // 平台总览：platformAdmin → 200，能看到平台池 provider 的授权行；组织 owner → 403。
        getJson("/api/platform/credit-overview", env.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'pc_owner_page')].grantId").exists());
        getJson("/api/platform/credit-overview", ownerToken)
                .andExpect(status().isForbidden());

        // 模型目录：登录用户可读；普通用户写 → 403。
        getJson("/api/model-catalog", ownerToken).andExpect(status().isOk());
        postJson("/api/model-catalog",
                        om.readTree("{\"modelName\":\"x\",\"creditCost\":1}"), ownerToken)
                .andExpect(status().isForbidden());
    }
}
