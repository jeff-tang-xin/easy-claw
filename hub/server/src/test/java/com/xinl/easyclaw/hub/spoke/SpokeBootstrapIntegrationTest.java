package com.xinl.easyclaw.hub.spoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinl.easyclaw.hub.contract.appkey.BindingRequest;
import com.xinl.easyclaw.hub.contract.appkey.CreateAppKeyRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.provider.CreateProviderRequest;
import com.xinl.easyclaw.hub.entity.LlmProviderEntity;
import com.xinl.easyclaw.hub.repository.LlmProviderRepository;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * spoke bootstrap 端到端：appkey 认证（控制面 ApiError 格式）→ 配置快照
 * （身份/组织/绑定展开的模型面/服务权限）。用户名统一 spk_ 前缀，slug 统一 spk- 前缀
 * （slug 仅允许小写字母/数字/连字符，不能带下划线）；不断言中文错误文案，断言稳定错误码。
 */
class SpokeBootstrapIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";
    private static final String FAKE_UPSTREAM = "http://127.0.0.1:1/v1";

    @Autowired
    private LlmProviderRepository providerRepository;

    // ---------- 认证面 ----------

    @Test
    void bootstrapWithoutKeyReturns401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/spoke/bootstrap"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void bootstrapWithBadKeyReturns401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/spoke/bootstrap")
                        .header("Authorization", "Bearer eck-00000000000000000000000000000000"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void bootstrapWithRevokedKeyReturns401() throws Exception {
        String ownerToken = createUserAndLogin("spk_rev_owner", PW);
        long orgId = createOrgOk(ownerToken, "Spk Rev Org", "spk-rev-org");
        JsonNode created = createAppKeyRaw(ownerToken, orgId, List.of());
        postJson("/api/orgs/" + orgId + "/appkeys/" + created.get("appKey").get("id").asLong() + "/revoke",
                null, ownerToken).andExpect(status().isNoContent());
        bootstrap(created.get("plainKey").asText())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    // ---------- 配置快照 ----------

    @Test
    void bootstrapReturnsFullSnapshot() throws Exception {
        String adminToken = newPlatformAdminToken("spk_snap_admin");
        String ownerToken = createUserAndLogin("spk_snap_owner", PW);
        long orgId = createOrgOk(ownerToken, "Spk Snap Org", "spk-snap-org");
        long openaiId = createProviderOk(adminToken, "spk-openai", "gpt-4o,gpt-4o-mini", "openai");
        long anthropicId = createProviderOk(adminToken, "spk-claude", "claude-a,claude-b", "anthropic");
        // openai 绑全部模型（modelName=""），anthropic 只绑 claude-a
        JsonNode created = createAppKeyRaw(ownerToken, orgId, List.of(
                new BindingRequest(openaiId, ""),
                new BindingRequest(anthropicId, "claude-a")));

        MvcResult result = bootstrap(created.get("plainKey").asText())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appKey.name").value("spk-key"))
                .andExpect(jsonPath("$.appKey.keyPrefix").value(created.get("plainKey").asText().substring(0, 12)))
                .andExpect(jsonPath("$.appKey.orgId").value(orgId))
                .andExpect(jsonPath("$.org.id").value(orgId))
                .andExpect(jsonPath("$.org.name").value("Spk Snap Org"))
                .andExpect(jsonPath("$.org.slug").value("spk-snap-org"))
                .andExpect(jsonPath("$.providers.length()").value(2))
                .andExpect(jsonPath("$.permissions[0]").value("llm.invoke"))
                .andReturn();

        JsonNode body = om.readTree(result.getResponse().getContentAsString());
        JsonNode openai = providerBySlug(body, "spk-openai");
        assertEquals("openai", openai.get("apiType").asText());
        assertEquals(List.of("gpt-4o", "gpt-4o-mini"), modelsOf(openai));
        JsonNode claude = providerBySlug(body, "spk-claude");
        assertEquals("anthropic", claude.get("apiType").asText());
        assertEquals(List.of("claude-a"), modelsOf(claude));
        // 绝不下发真实 key 材料与 baseUrl
        for (JsonNode p : body.get("providers")) {
            assertTrue(p.get("baseUrl") == null && p.get("apiKeyCiphertext") == null,
                    "provider 快照不得携带 baseUrl/key 材料");
        }
    }

    @Test
    void bootstrapSkipsDisabledProvider() throws Exception {
        String adminToken = newPlatformAdminToken("spk_dis_admin");
        String ownerToken = createUserAndLogin("spk_dis_owner", PW);
        long orgId = createOrgOk(ownerToken, "Spk Dis Org", "spk-dis-org");
        long providerId = createProviderOk(adminToken, "spk-disabled", "m1,m2", "openai");
        JsonNode created = createAppKeyRaw(ownerToken, orgId, List.of(new BindingRequest(providerId, "")));
        // 绑定后禁用 provider（直改库，聚焦 bootstrap 的过滤口径而非 disable 流程）
        LlmProviderEntity p = providerRepository.findById(providerId).orElseThrow();
        p.setStatus("disabled");
        providerRepository.save(p);

        bootstrap(created.get("plainKey").asText())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers.length()").value(0))
                .andExpect(jsonPath("$.permissions[0]").value("llm.invoke"));
    }

    @Test
    void bootstrapWithoutBindingsReturnsEmptyProviders() throws Exception {
        String ownerToken = createUserAndLogin("spk_nb_owner", PW);
        long orgId = createOrgOk(ownerToken, "Spk Nb Org", "spk-nb-org");
        JsonNode created = createAppKeyRaw(ownerToken, orgId, List.of());

        bootstrap(created.get("plainKey").asText())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers.length()").value(0))
                .andExpect(jsonPath("$.org.id").value(orgId));
    }

    // ---------- 辅助 ----------

    private org.springframework.test.web.servlet.ResultActions bootstrap(String appKey) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/spoke/bootstrap")
                .header("Authorization", "Bearer " + appKey));
    }

    private String newPlatformAdminToken(String username) throws Exception {
        createPlatformAdminOk(username, PW);
        return loginOk(username, PW).accessToken();
    }

    private static List<String> models(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).toList();
    }

    private long createProviderOk(String adminToken, String slug, String models, String apiType) throws Exception {
        String json = postJson("/api/providers",
                        new CreateProviderRequest(slug, "Name " + slug, FAKE_UPSTREAM, "sk-fake-" + slug,
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

    /** 颁发 appkey 并断言 201，返回完整响应 JSON（appKey 子对象 + plainKey）。 */
    private JsonNode createAppKeyRaw(String ownerToken, long orgId, List<BindingRequest> bindings)
            throws Exception {
        String json = postJson("/api/orgs/" + orgId + "/appkeys",
                        new CreateAppKeyRequest("spk-key", bindings), ownerToken)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json);
    }

    private static JsonNode providerBySlug(JsonNode bootstrapBody, String slug) {
        for (JsonNode p : bootstrapBody.get("providers")) {
            if (slug.equals(p.get("slug").asText())) {
                return p;
            }
        }
        throw new AssertionError("provider 不在快照中：" + slug);
    }

    private static List<String> modelsOf(JsonNode provider) {
        List<String> out = new java.util.ArrayList<>();
        provider.get("models").forEach(m -> out.add(m.asText()));
        return out;
    }
}
