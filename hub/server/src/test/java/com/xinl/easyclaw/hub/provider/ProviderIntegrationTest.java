package com.xinl.easyclaw.hub.provider;

import com.xinl.easyclaw.hub.contract.appkey.BindingRequest;
import com.xinl.easyclaw.hub.contract.appkey.CreateAppKeyRequest;
import com.xinl.easyclaw.hub.contract.appkey.UpdateBindingsRequest;
import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.provider.CreateProviderRequest;
import com.xinl.easyclaw.hub.contract.provider.UpdateProviderRequest;
import com.xinl.easyclaw.hub.entity.LlmProviderEntity;
import com.xinl.easyclaw.hub.repository.LlmProviderRepository;
import com.xinl.easyclaw.hub.service.CryptoService;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LLM provider 平台池：登录即可列表 / 平台管理员门禁（增改删）/ slug 唯一与格式校验 /
 * apiKey AES-GCM 落库、响应只回尾 4 位 keyHint / 删除保护（仍被 appkey 绑定 → 409）。
 * 用户名统一 pv_ 前缀，slug 统一 pv- 前缀。
 * 删除保护只认现存绑定：revoke 会同事务清空绑定（2026-09-15 修复设计缺口，旧行为见黑板 #12），
 * 「绑定中 → 409，revoke 后绑定清空 → 204」由 delete_afterBoundAppKeyRevoked_allowed 固化。
 */
class ProviderIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    @Autowired
    private LlmProviderRepository providerRepository;

    @Autowired
    private CryptoService cryptoService;

    // ---------- 辅助 ----------

    private String newPlatformAdminToken(String username) throws Exception {
        createPlatformAdminOk(username, PW);
        return loginOk(username, PW).accessToken();
    }

    /** 逗号串 → models 清单（contract 已改为 List&lt;String&gt;，本辅助保留旧用例的逗号串写法）。 */
    private static List<String> models(String csv) {
        if (csv == null) {
            return null;
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 平台管理员建平台池 provider 并断言 201，返回 providerId。 */
    private long createProviderOk(String adminToken, String slug, String apiKey, String models) throws Exception {
        return createProviderOk(adminToken, slug, apiKey, models, null);
    }

    /** 建 provider 并断言 201（orgId 为 null 即平台池），返回 providerId。 */
    private long createProviderOk(String token, String slug, String apiKey, String models, Long orgId) throws Exception {
        String json = postJson("/api/providers",
                        new CreateProviderRequest(slug, "Name " + slug, "https://api.example.com", apiKey,
                                models(models), null, orgId, null),
                        token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private long createOrgOk(String ownerToken, String name, String slug) throws Exception {
        String json = postJson("/api/orgs", new CreateOrgRequest(name, slug), ownerToken)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    /** PUT 不在基座辅助内：本类私有实现（带不带 token 均可）。 */
    private ResultActions putJson(String url, Object body, String accessToken) throws Exception {
        var builder = MockMvcRequestBuilders.put(url);
        if (accessToken != null) {
            builder = builder.header("Authorization", "Bearer " + accessToken);
        }
        return mvc.perform(builder
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }

    // ---------- 列表 ----------

    @Test
    void list_requiresLogin() throws Exception {
        getJson("/api/providers", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void list_anyLoggedInUser_seesKeyHintButNeverPlainKey() throws Exception {
        String admin = newPlatformAdminToken("pv_adm1");
        String plainKey = "sk-pvlist-1234567890abcd";
        createProviderOk(admin, "pv-list", plainKey, "gpt-4o, gpt-4o-mini");

        // 普通登录用户（非平台管理员）即可列表，只看到掩码 keyHint。
        String viewer = createUserAndLogin("pv_viewer1", PW);
        String body = getJson("/api/providers", viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug=='pv-list')].keyHint", hasItem("****abcd")))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(plainKey), "任何响应不得回明文 apiKey");
    }

    // ---------- 创建 ----------

    @Test
    void create_success_encryptsKeyAndReturnsKeyHintOnly() throws Exception {
        String admin = newPlatformAdminToken("pv_adm2");
        String plainKey = "sk-pvcreate-abcdef0123456789wxyz";
        String body = postJson("/api/providers",
                        new CreateProviderRequest("pv-openai", "PV OpenAI", "https://api.openai.com",
                                plainKey, models("gpt-4o, gpt-4o-mini"), "pv-remark", null, null),
                        admin)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("pv-openai"))
                .andExpect(jsonPath("$.name").value("PV OpenAI"))
                .andExpect(jsonPath("$.baseUrl").value("https://api.openai.com"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.models", containsInAnyOrder("gpt-4o", "gpt-4o-mini")))
                .andExpect(jsonPath("$.keyHint").value("****wxyz"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(plainKey), "创建响应不得回明文 apiKey");

        // 落库为 AES-GCM 密文：不等于明文、不含明文，可用同主密钥解回。
        LlmProviderEntity saved = providerRepository.findBySlug("pv-openai").orElseThrow();
        assertNotEquals(plainKey, saved.getApiKeyCiphertext());
        assertFalse(saved.getApiKeyCiphertext().contains(plainKey));
        assertEquals(plainKey, cryptoService.decrypt(saved.getApiKeyCiphertext()));
    }

    @Test
    void create_orgOwnerButNotPlatformAdmin_forbidden() throws Exception {
        String owner = createUserAndLogin("pv_owner3", PW);
        // 坐实「组织 owner」身份：建组织成功 ≠ 平台管理员。
        postJson("/api/orgs", new CreateOrgRequest("Pv Org3", "pv-org3"), owner)
                .andExpect(status().isOk());
        postJson("/api/providers",
                        new CreateProviderRequest("pv-denied", "Denied", "https://api.example.com", "sk-x",
                                null, null, null, null),
                        owner)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void create_duplicateSlug_conflict() throws Exception {
        String admin = newPlatformAdminToken("pv_adm4");
        createProviderOk(admin, "pv-dup", "sk-dup-1", null);
        postJson("/api/providers",
                        new CreateProviderRequest("pv-dup", "Dup2", "https://api2.example.com", "sk-dup-2",
                                null, null, null, null),
                        admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void create_invalidSlug_validation() throws Exception {
        String admin = newPlatformAdminToken("pv_adm5");
        postJson("/api/providers",
                        new CreateProviderRequest("Pv Bad Slug", "Bad", "https://api.example.com", "sk-x",
                                null, null, null, null),
                        admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    // ---------- 更新 ----------

    @Test
    void update_rotatesKeyOnDemandAndValidatesStatus() throws Exception {
        String admin = newPlatformAdminToken("pv_adm6");
        long id = createProviderOk(admin, "pv-upd", "sk-old-0000oldk", "m1");

        // 不给 apiKey：密文不动，keyHint 保持旧 key 尾 4 位。
        patchJson("/api/providers/" + id,
                        new UpdateProviderRequest("PV Upd Renamed", null, null, models("m1, m2"), null, null, null), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("PV Upd Renamed"))
                .andExpect(jsonPath("$.models", containsInAnyOrder("m1", "m2")))
                .andExpect(jsonPath("$.keyHint").value("****oldk"));

        // 给 apiKey：换密文，keyHint 跟随新 key。
        patchJson("/api/providers/" + id,
                        new UpdateProviderRequest(null, null, "sk-new-0000newk", null, "disabled", null, null), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("disabled"))
                .andExpect(jsonPath("$.keyHint").value("****newk"));
        LlmProviderEntity saved = providerRepository.findById(id).orElseThrow();
        assertEquals("sk-new-0000newk", cryptoService.decrypt(saved.getApiKeyCiphertext()));

        // 非法 status → 400；不存在 → 404；非平台管理员 → 403。
        patchJson("/api/providers/" + id, new UpdateProviderRequest(null, null, null, null, "weird", null, null), admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        patchJson("/api/providers/999999999", new UpdateProviderRequest("X", null, null, null, null, null, null), admin)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        String user = createUserAndLogin("pv_user6", PW);
        patchJson("/api/providers/" + id, new UpdateProviderRequest("Y", null, null, null, null, null, null), user)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ---------- 删除 ----------

    @Test
    void delete_requiresPlatformAdmin_andRemovesRow() throws Exception {
        String admin = newPlatformAdminToken("pv_adm7");
        long id = createProviderOk(admin, "pv-del", "sk-del-0000delk", null);

        String user = createUserAndLogin("pv_user7", PW);
        deleteJson("/api/providers/" + id, user)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        deleteJson("/api/providers/999999999", admin)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        deleteJson("/api/providers/" + id, admin)
                .andExpect(status().isNoContent());
        assertTrue(providerRepository.findById(id).isEmpty());
    }

    @Test
    void delete_boundByAppKey_conflict_untilBindingsCleared() throws Exception {
        String admin = newPlatformAdminToken("pv_adm8");
        long providerId = createProviderOk(admin, "pv-bound", "sk-bound-0000bndk", "m1,m2");
        String owner = createUserAndLogin("pv_owner8", PW);
        long orgId = createOrgOk(owner, "Pv Org8", "pv-org8");
        String keyJson = postJson("/api/orgs/" + orgId + "/appkeys",
                        new CreateAppKeyRequest("pv-key8", List.of(new BindingRequest(providerId, "m1"))), owner)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long keyId = om.readTree(keyJson).get("appKey").get("id").asLong();

        // 仍被绑定：删除 409。
        deleteJson("/api/providers/" + providerId, admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // key 仍 active：全量清空绑定 → 删除放行（204）。
        putJson("/api/orgs/" + orgId + "/appkeys/" + keyId + "/bindings",
                        new UpdateBindingsRequest(List.of()), owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindings", hasSize(0)));
        deleteJson("/api/providers/" + providerId, admin)
                .andExpect(status().isNoContent());
        assertTrue(providerRepository.findById(providerId).isEmpty());
    }

    @Test
    void delete_afterBoundAppKeyRevoked_allowedSinceRevokeClearsBindings() throws Exception {
        // 现行行为（2026-09-15 修复设计缺口，见 AppKeyService.revoke）：revoke 同事务清空绑定。
        // 绑定中删除 provider → 409；吊销 appkey（绑定随吊销清空）后 → 204，provider 不再死锁。
        // 「已吊销 key 禁止改绑定」不变，一并在本用例锁定。
        String admin = newPlatformAdminToken("pv_adm9");
        long providerId = createProviderOk(admin, "pv-stuck", "sk-stuck-0000stuk", "m1");
        String owner = createUserAndLogin("pv_owner9", PW);
        long orgId = createOrgOk(owner, "Pv Org9", "pv-org9");
        String keyJson = postJson("/api/orgs/" + orgId + "/appkeys",
                        new CreateAppKeyRequest("pv-key9", List.of(new BindingRequest(providerId, "m1"))), owner)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long keyId = om.readTree(keyJson).get("appKey").get("id").asLong();

        // 绑定中：删除 409。
        deleteJson("/api/providers/" + providerId, admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 吊销（绑定随吊销清空）→ 删除放行 204。
        postJson("/api/orgs/" + orgId + "/appkeys/" + keyId + "/revoke", null, owner)
                .andExpect(status().isNoContent());
        deleteJson("/api/providers/" + providerId, admin)
                .andExpect(status().isNoContent());
        assertTrue(providerRepository.findById(providerId).isEmpty(), "revoke 清空绑定后 provider 应可删除");

        // 已吊销 key 仍禁止改绑定（现行设计，锁住）。
        putJson("/api/orgs/" + orgId + "/appkeys/" + keyId + "/bindings",
                        new UpdateBindingsRequest(List.of()), owner)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    // ---------- 组织作用域（V5：org_id + api_type） ----------

    @Test
    void create_orgProvider_byOwner_success_scopedSlugCoexists() throws Exception {
        String owner = createUserAndLogin("pv_owner10", PW);
        long orgId = createOrgOk(owner, "Pv OrgA10", "pv-orga10");

        // 组织 owner 建本组织 provider：apiType=anthropic，响应回 orgId/orgName/apiType。
        postJson("/api/providers",
                        new CreateProviderRequest("pv-anth", "Pv Anthropic", "https://api.anthropic.com",
                                "sk-ant-0000anth", models("claude-opus-4, claude-sonnet-4"), null, orgId, "anthropic"),
                        owner)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orgId").value(orgId))
                .andExpect(jsonPath("$.orgName").value("Pv OrgA10"))
                .andExpect(jsonPath("$.apiType").value("anthropic"))
                .andExpect(jsonPath("$.models", containsInAnyOrder("claude-opus-4", "claude-sonnet-4")))
                .andExpect(jsonPath("$.keyHint").value("****anth"));

        // 同 slug：平台池（orgId=null）可并存；同组织再建同 slug → 409。
        String admin = newPlatformAdminToken("pv_adm10");
        createProviderOk(admin, "pv-anth", "sk-pool-0000pool", null);
        postJson("/api/providers",
                        new CreateProviderRequest("pv-anth", "Dup Org", "https://api.anthropic.com",
                                "sk-ant-2", null, null, orgId, null),
                        owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void create_orgProvider_byPlainMember_forbidden() throws Exception {
        String owner = createUserAndLogin("pv_owner11", PW);
        long orgId = createOrgOk(owner, "Pv OrgA11", "pv-orga11");
        createUserOk("pv_mem11", null, PW);
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("pv_mem11", "member"), owner)
                .andExpect(status().isCreated());
        String member = loginOk("pv_mem11", PW).accessToken();

        // 普通成员（非 owner/admin）建组织 provider → 403。
        postJson("/api/providers",
                        new CreateProviderRequest("pv-orgprov", "Org Prov", "https://api.example.com",
                                "sk-x", null, null, orgId, null),
                        member)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 不存在的 orgId → 400。
        postJson("/api/providers",
                        new CreateProviderRequest("pv-noorg", "No Org", "https://api.example.com",
                                "sk-x", null, null, 999999999L, null),
                        owner)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void create_invalidApiType_validation() throws Exception {
        String admin = newPlatformAdminToken("pv_adm12");
        postJson("/api/providers",
                        new CreateProviderRequest("pv-badtype", "Bad Type", "https://api.example.com",
                                "sk-x", null, null, null, "weird"),
                        admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void list_visibility_scopedByOrg() throws Exception {
        String admin = newPlatformAdminToken("pv_adm13");
        createProviderOk(admin, "pv-pool", "sk-pool-0000pool", null);

        String ownerA = createUserAndLogin("pv_owner13a", PW);
        long orgA = createOrgOk(ownerA, "Pv OrgA13", "pv-orga13");
        createProviderOk(ownerA, "pv-orgaprov", "sk-orga-0000orga", null, orgA);

        String ownerB = createUserAndLogin("pv_owner13b", PW);
        createOrgOk(ownerB, "Pv OrgB13", "pv-orgb13");

        // orgA 成员：平台池 + 本组织 provider 均可见。
        String bodyA = getJson("/api/providers", ownerA)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(bodyA.contains("\"slug\":\"pv-pool\""), "orgA 成员应见平台池 provider");
        assertTrue(bodyA.contains("\"slug\":\"pv-orgaprov\""), "orgA 成员应见本组织 provider");

        // orgB 成员：只见平台池，不见 orgA provider（存在性也不泄露）。
        String bodyB = getJson("/api/providers", ownerB)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(bodyB.contains("\"slug\":\"pv-pool\""), "orgB 成员应见平台池 provider");
        assertFalse(bodyB.contains("pv-orgaprov"), "orgB 成员不得见 orgA provider");

        // 平台管理员：全量可见。
        String bodyAdmin = getJson("/api/providers", admin)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(bodyAdmin.contains("\"slug\":\"pv-pool\""));
        assertTrue(bodyAdmin.contains("\"slug\":\"pv-orgaprov\""));
    }

    @Test
    void update_delete_orgProvider_crossOrg_forbidden() throws Exception {
        String ownerA = createUserAndLogin("pv_owner14a", PW);
        long orgA = createOrgOk(ownerA, "Pv OrgA14", "pv-orga14");
        long id = createProviderOk(ownerA, "pv-cross", "sk-cross-0000cros", "m1", orgA);

        // 跨组织 ownerB：改/删 orgA provider → 403（可见但无权）。
        String ownerB = createUserAndLogin("pv_owner14b", PW);
        createOrgOk(ownerB, "Pv OrgB14", "pv-orgb14");
        patchJson("/api/providers/" + id, new UpdateProviderRequest("Hijack", null, null, null, null, null, null),
                        ownerB)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        deleteJson("/api/providers/" + id, ownerB)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 本组织 owner：改（含 apiType）删均放行。
        patchJson("/api/providers/" + id,
                        new UpdateProviderRequest("Pv Cross Renamed", null, null, null, null, null, "anthropic"),
                        ownerA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pv Cross Renamed"))
                .andExpect(jsonPath("$.apiType").value("anthropic"))
                .andExpect(jsonPath("$.orgId").value(orgA));
        deleteJson("/api/providers/" + id, ownerA)
                .andExpect(status().isNoContent());
        assertTrue(providerRepository.findById(id).isEmpty());
    }
}
