package com.xinl.easyclaw.hub.appkey;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinl.easyclaw.hub.contract.appkey.BindingRequest;
import com.xinl.easyclaw.hub.contract.appkey.CreateAppKeyRequest;
import com.xinl.easyclaw.hub.contract.appkey.UpdateBindingsRequest;
import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.provider.CreateProviderRequest;
import com.xinl.easyclaw.hub.entity.AppKeyEntity;
import com.xinl.easyclaw.hub.repository.AppKeyProviderBindingRepository;
import com.xinl.easyclaw.hub.repository.AppKeyRepository;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 组织级 appkey：生命周期（颁发/列表/改绑/吊销幂等）/ 绑定校验（provider 存在且 active、
 * modelName 须在 models 清单内、空白=全部模型、同对去重）/ 角色与跨 org 隔离 / 库内只存 SHA-256 不落明文。
 * provider 只能由平台管理员创建，用例统一先建 provider 再发 key。
 * 用户名统一 ak_ 前缀，slug 统一 ak- 前缀。
 */
class AppKeyIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    @Autowired
    private AppKeyRepository appKeyRepository;

    @Autowired
    private AppKeyProviderBindingRepository bindingRepository;

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
        String json = postJson("/api/providers",
                        new CreateProviderRequest(slug, "Name " + slug, "https://api.example.com", apiKey,
                                models(models), null, null, null),
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

    private static String appkeysUrl(long orgId) {
        return "/api/orgs/" + orgId + "/appkeys";
    }

    // ---------- 生命周期 ----------

    @Test
    void lifecycle_createListUpdateBindingsRevokeIdempotent() throws Exception {
        String admin = newPlatformAdminToken("ak_adm1");
        long providerId = createProviderOk(admin, "ak-p1", "sk-ak1-0000prov", "gpt-4o,gpt-4o-mini");
        String owner = createUserAndLogin("ak_owner1", PW);
        long orgId = createOrgOk(owner, "Ak Org1", "ak-org1");

        // 颁发：201，plainKey 形如 eck-<32 位 hex>，keyPrefix = plainKey 前 12 字符。
        String created = postJson(appkeysUrl(orgId),
                        new CreateAppKeyRequest("ak-key1", List.of(new BindingRequest(providerId, "gpt-4o"))), owner)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appKey.name").value("ak-key1"))
                .andExpect(jsonPath("$.appKey.orgId").value(orgId))
                .andExpect(jsonPath("$.appKey.status").value("active"))
                .andExpect(jsonPath("$.appKey.bindings", hasSize(1)))
                .andExpect(jsonPath("$.appKey.bindings[0].providerId").value(providerId))
                .andExpect(jsonPath("$.appKey.bindings[0].providerSlug").value("ak-p1"))
                .andExpect(jsonPath("$.appKey.bindings[0].modelName").value("gpt-4o"))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = om.readTree(created);
        String plainKey = node.get("plainKey").asText();
        long keyId = node.get("appKey").get("id").asLong();
        assertTrue(plainKey.matches("^eck-[0-9a-f]{32}$"), "plainKey 格式应为 eck-<32 位 hex>，实际：" + plainKey);
        assertEquals(plainKey.substring(0, 12), node.get("appKey").get("keyPrefix").asText());

        // 列表：不出现 plainKey，只看到 keyPrefix 与绑定。
        String listBody = getJson(appkeysUrl(orgId), owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(keyId))
                .andExpect(jsonPath("$[0].keyPrefix").value(plainKey.substring(0, 12)))
                .andReturn().getResponse().getContentAsString();
        assertFalse(listBody.contains(plainKey), "列表响应不得回明文 plainKey");

        // 改绑：全量替换为另一个模型。
        putJson(appkeysUrl(orgId) + "/" + keyId + "/bindings",
                        new UpdateBindingsRequest(List.of(new BindingRequest(providerId, "gpt-4o-mini"))), owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindings", hasSize(1)))
                .andExpect(jsonPath("$.bindings[0].modelName").value("gpt-4o-mini"));

        // 吊销：204 且幂等；revoke 同事务清空绑定。
        postJson(appkeysUrl(orgId) + "/" + keyId + "/revoke", null, owner)
                .andExpect(status().isNoContent());
        postJson(appkeysUrl(orgId) + "/" + keyId + "/revoke", null, owner)
                .andExpect(status().isNoContent());

        // 已吊销 key 禁止改绑定。
        putJson(appkeysUrl(orgId) + "/" + keyId + "/bindings",
                        new UpdateBindingsRequest(List.of(new BindingRequest(providerId, "gpt-4o"))), owner)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        // 终态：revoked + revokedAt 非空 + 绑定已清空。
        getJson(appkeysUrl(orgId), owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("revoked"))
                .andExpect(jsonPath("$[0].revokedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].bindings", hasSize(0)));
        assertTrue(bindingRepository.findByAppKeyId(keyId).isEmpty(), "revoke 应同事务清空绑定");
    }

    // ---------- 绑定校验 ----------

    @Test
    void create_bindingUnknownProviderOrUnlistedModel_validation() throws Exception {
        String admin = newPlatformAdminToken("ak_adm2");
        long providerId = createProviderOk(admin, "ak-p2", "sk-ak2-0000prov", "m1,m2");
        String owner = createUserAndLogin("ak_owner2", PW);
        long orgId = createOrgOk(owner, "Ak Org2", "ak-org2");

        // providerId 不存在 → 400。
        postJson(appkeysUrl(orgId),
                        new CreateAppKeyRequest("ak-key2a", List.of(new BindingRequest(999999999L, null))), owner)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        // modelName 不在 provider models 清单 → 400。
        postJson(appkeysUrl(orgId),
                        new CreateAppKeyRequest("ak-key2b", List.of(new BindingRequest(providerId, "m3"))), owner)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        // 校验失败不得残留 key。
        assertTrue(appKeyRepository.findByOrgIdOrderByIdDesc(orgId).isEmpty(), "绑定校验失败不应落库 appkey");
    }

    @Test
    void create_blankModelNameMeansAllModels_andDuplicatesDeduplicated() throws Exception {
        String admin = newPlatformAdminToken("ak_adm3");
        long providerId = createProviderOk(admin, "ak-p3", "sk-ak3-0000prov", "m1,m2");
        String owner = createUserAndLogin("ak_owner3", PW);
        long orgId = createOrgOk(owner, "Ak Org3", "ak-org3");

        // modelName 省略(null)/空白 → 空串（= 该 provider 全部模型）；非空白先 trim 再校验；
        // 同 (providerId, modelName) 重复行去重：4 行请求规整为 ("", "m1") 两条。
        String created = postJson(appkeysUrl(orgId),
                        new CreateAppKeyRequest("ak-key3", List.of(
                                new BindingRequest(providerId, null),
                                new BindingRequest(providerId, "   "),
                                new BindingRequest(providerId, "m1"),
                                new BindingRequest(providerId, " m1 "))), owner)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appKey.bindings", hasSize(2)))
                .andExpect(jsonPath("$.appKey.bindings[*].modelName", containsInAnyOrder("", "m1")))
                .andReturn().getResponse().getContentAsString();
        long keyId = om.readTree(created).get("appKey").get("id").asLong();
        assertEquals(2, bindingRepository.findByAppKeyId(keyId).size(), "去重后应只落 2 条绑定");
    }

    // ---------- 角色与跨 org 隔离 ----------

    @Test
    void appkey_memberForbidden_andCrossOrgIsolation() throws Exception {
        String admin = newPlatformAdminToken("ak_adm4");
        long providerId = createProviderOk(admin, "ak-p4", "sk-ak4-0000prov", "m1");
        String ownerA = createUserAndLogin("ak_ownerA4", PW);
        long orgA = createOrgOk(ownerA, "Ak OrgA4", "ak-orga4");
        String memberA = createUserAndLogin("ak_memA4", PW);
        postJson("/api/orgs/" + orgA + "/members", new AddMemberRequest("ak_memA4", "member"), ownerA)
                .andExpect(status().isCreated());

        // member（非 owner/admin）：颁发与列表都 403。
        postJson(appkeysUrl(orgA), new CreateAppKeyRequest("ak-keyA4", null), memberA)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        getJson(appkeysUrl(orgA), memberA)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // orgA owner 正常颁发一把 key。
        String created = postJson(appkeysUrl(orgA),
                        new CreateAppKeyRequest("ak-keyA4", List.of(new BindingRequest(providerId, "m1"))), ownerA)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long keyId = om.readTree(created).get("appKey").get("id").asLong();

        // orgB owner 不是 orgA 成员：GET orgA 的列表 → 403。
        String ownerB = createUserAndLogin("ak_ownerB4", PW);
        long orgB = createOrgOk(ownerB, "Ak OrgB4", "ak-orgb4");
        getJson(appkeysUrl(orgA), ownerB)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 防跨 org 探测：orgB owner 用自己 org 的路径操作 orgA 的 keyId → 404（不暴露存在性）。
        postJson(appkeysUrl(orgB) + "/" + keyId + "/revoke", null, ownerB)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        putJson(appkeysUrl(orgB) + "/" + keyId + "/bindings",
                        new UpdateBindingsRequest(List.of(new BindingRequest(providerId, "m1"))), ownerB)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 上述跨 org 尝试不得产生副作用：key 仍 active、绑定仍在。
        getJson(appkeysUrl(orgA), ownerA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("active"))
                .andExpect(jsonPath("$[0].bindings", hasSize(1)));
    }

    // ---------- 库内不落明文 ----------

    @Test
    void create_storesSha256HashOnly_neverPlainKey() throws Exception {
        String admin = newPlatformAdminToken("ak_adm5");
        long providerId = createProviderOk(admin, "ak-p5", "sk-ak5-0000prov", "m1");
        String owner = createUserAndLogin("ak_owner5", PW);
        long orgId = createOrgOk(owner, "Ak Org5", "ak-org5");

        String created = postJson(appkeysUrl(orgId),
                        new CreateAppKeyRequest("ak-key5", List.of(new BindingRequest(providerId, "m1"))), owner)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = om.readTree(created);
        String plainKey = node.get("plainKey").asText();
        long keyId = node.get("appKey").get("id").asLong();

        // 落库：keyHash 是 64 位 hex 且等于 SHA-256(plainKey)，keyPrefix 只是前 12 字符展示前缀，
        // 任何字段都不是完整明文（keyPrefix 为设计允许的短前缀，不含完整 key）。
        AppKeyEntity saved = appKeyRepository.findById(keyId).orElseThrow();
        assertTrue(saved.getKeyHash().matches("[0-9a-f]{64}"), "keyHash 应为 64 位 hex，实际：" + saved.getKeyHash());
        assertNotEquals(plainKey, saved.getKeyHash());
        assertEquals(sha256Hex(plainKey), saved.getKeyHash(), "keyHash 应是 plainKey 的 SHA-256");
        assertEquals(plainKey.substring(0, 12), saved.getKeyPrefix());
        assertFalse(saved.getKeyHash().contains(plainKey));
        assertFalse(saved.getName().contains(plainKey));
    }

    private static String sha256Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ---------- 组织作用域 provider 的绑定可见性（V5） ----------

    @Test
    void bind_orgScopedProvider_visibleScopeEnforced() throws Exception {
        String admin = newPlatformAdminToken("ak_adm6");
        long poolId = createProviderOk(admin, "ak-pool", "sk-pool-0000pool", "m1");

        // orgA 的 provider（ownerA 自建本组织 provider，V5 起组织 owner/admin 可建）。
        String ownerA = createUserAndLogin("ak_owner6a", PW);
        long orgA = createOrgOk(ownerA, "Ak OrgA6", "ak-orga6");
        String orgProvJson = postJson("/api/providers",
                        new CreateProviderRequest("ak-orgaprov", "OrgA Prov", "https://api.example.com",
                                "sk-orga-0000orga", models("m1"), null, orgA, null),
                        ownerA)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orgProvId = om.readTree(orgProvJson).get("id").asLong();

        // 本组织 appkey：可绑平台池 + 本组织 provider。
        postJson(appkeysUrl(orgA),
                        new CreateAppKeyRequest("ak-key6a", List.of(
                                new BindingRequest(poolId, null),
                                new BindingRequest(orgProvId, "m1"))),
                        ownerA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appKey.bindings", hasSize(2)));

        // 他组织 appkey 绑 orgA provider → 400（按「provider 不存在」报，不泄露存在性）。
        String ownerB = createUserAndLogin("ak_owner6b", PW);
        long orgB = createOrgOk(ownerB, "Ak OrgB6", "ak-orgb6");
        postJson(appkeysUrl(orgB),
                        new CreateAppKeyRequest("ak-key6b", List.of(new BindingRequest(orgProvId, "m1"))),
                        ownerB)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }
}
