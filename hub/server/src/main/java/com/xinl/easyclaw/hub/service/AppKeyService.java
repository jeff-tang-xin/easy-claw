package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.contract.appkey.AppKeyBindingDto;
import com.xinl.easyclaw.hub.contract.appkey.AppKeyCreatedResponse;
import com.xinl.easyclaw.hub.contract.appkey.AppKeyDto;
import com.xinl.easyclaw.hub.contract.appkey.CloudRouteRequest;
import com.xinl.easyclaw.hub.contract.appkey.BindingRequest;
import com.xinl.easyclaw.hub.contract.appkey.CreateAppKeyRequest;
import com.xinl.easyclaw.hub.contract.appkey.UpdateBindingsRequest;
import com.xinl.easyclaw.hub.entity.AppKeyEntity;
import com.xinl.easyclaw.hub.entity.AppKeyProviderBindingEntity;
import com.xinl.easyclaw.hub.entity.LlmProviderEntity;
import com.xinl.easyclaw.hub.repository.AppKeyProviderBindingRepository;
import com.xinl.easyclaw.hub.repository.AppKeyRepository;
import com.xinl.easyclaw.hub.repository.LlmProviderRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织级 appkey：每个人都有自己的 appkey（appkey.self）——owner/admin/member 均可颁发，
 * 创建者本人恒可管理自己的 key；owner/admin 另可治理组织内全部（appkey.manage）。
 * 明文 key 形如 eck-&lt;32 位 hex&gt;，仅创建时返回一次；库中只存 SHA-256 hash + 展示前缀（前 12 字符）。
 * 绑定可精确到模型（modelName 空串 = 该 provider 全部模型），全量替换式更新。
 * 审计 detail 只放 name/keyPrefix，绝不落明文 key 与 hash。
 */
@Service
public class AppKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppKeyRepository appKeys;
    private final AppKeyProviderBindingRepository bindings;
    private final LlmProviderRepository providers;
    private final OrgService orgService;
    private final AuditService auditService;

    public AppKeyService(AppKeyRepository appKeys, AppKeyProviderBindingRepository bindings,
                         LlmProviderRepository providers, OrgService orgService, AuditService auditService) {
        this.appKeys = appKeys;
        this.bindings = bindings;
        this.providers = providers;
        this.orgService = orgService;
        this.auditService = auditService;
    }

    /**
     * appkey 列表：owner/admin 看组织全量（appkey.manage）；member 仅看自己创建的（appkey.self 个人视图）；
     * guest 无 appkey 权限。非组织成员 403。
     */
    @Transactional(readOnly = true)
    public List<AppKeyDto> list(Long actorId, Long orgId) {
        String role = orgService.roleOf(orgId, actorId);
        if (role == null) {
            throw ApiException.forbidden("非组织成员");
        }
        if ("guest".equals(role)) {
            throw ApiException.forbidden("权限不足（需 owner/admin/member）");
        }
        List<AppKeyEntity> keys = "owner".equals(role) || "admin".equals(role)
                ? appKeys.findByOrgIdOrderByIdDesc(orgId)
                : appKeys.findByOrgIdAndCreatedByOrderByIdDesc(orgId, actorId);
        if (keys.isEmpty()) {
            return List.of();
        }
        Map<Long, List<AppKeyProviderBindingEntity>> byKey = bindings.findByAppKeyIdIn(
                        keys.stream().map(AppKeyEntity::getId).toList())
                .stream().collect(Collectors.groupingBy(AppKeyProviderBindingEntity::getAppKeyId));
        Map<Long, LlmProviderEntity> providerMap = providerMapOf(byKey.values());
        return keys.stream()
                .map(k -> toDto(k, byKey.getOrDefault(k.getId(), List.of()), providerMap))
                .toList();
    }

    /** 颁发 appkey（owner/admin/member——每个人都有自己的 appkey）：明文仅此一次随响应返回。 */
    @Transactional
    public AppKeyCreatedResponse create(Long actorId, Long orgId, CreateAppKeyRequest req) {
        orgService.requireOrgRole(orgId, actorId, "owner", "admin", "member");
        AppKeyEntity k = new AppKeyEntity();
        k.setOrgId(orgId);
        k.setName(req.name().trim());
        String plainKey = newKeyString();
        k.setKeyPrefix(plainKey.substring(0, 12));
        k.setKeyHash(sha256(plainKey));
        k.setCreatedBy(actorId);
        appKeys.save(k);
        List<AppKeyProviderBindingEntity> saved = saveBindings(k.getId(), normalizeBindings(orgId, req.bindings()));
        if (req.cloudRoute() != null) {
            applyCloudRoute(k, orgId, req.cloudRoute(), saved);
        }
        auditService.record(AuditModule.APPKEY, "create_appkey", actorId, orgId, "app_key",
                String.valueOf(k.getId()), "name=" + k.getName() + ",keyPrefix=" + k.getKeyPrefix(),
                AuditModule.SUCCESS);
        return new AppKeyCreatedResponse(toDto(k, saved, providerMapOf(List.of(saved))), plainKey);
    }

    /**
     * 吊销（owner/admin）：幂等——已吊销直接返回；key 不属于该 org 一律 404，防跨 org 探测。
     * 吊销同事务清空 provider 绑定：revoked 是终态，绑定无保留价值（历史见审计），且避免「已吊销 key 的
     * 残留绑定让 provider 永远无法删除」的保护死锁（2026-09-15 子任务实测发现的缺口）。
     */
    @Transactional
    public void revoke(Long actorId, Long orgId, Long keyId) {
        orgService.requireOrgRole(orgId, actorId, "owner", "admin", "member");
        AppKeyEntity k = requireOrgKey(orgId, keyId);
        requireCanManageKey(orgId, actorId, k);
        if ("revoked".equals(k.getStatus())) {
            return;
        }
        k.setStatus("revoked");
        k.setRevokedAt(Instant.now());
        bindings.deleteByAppKeyId(keyId);
        clearCloudRoute(k);
        appKeys.save(k);
        auditService.record(AuditModule.APPKEY, "revoke_appkey", actorId, orgId, "app_key",
                String.valueOf(k.getId()), "keyPrefix=" + k.getKeyPrefix(), AuditModule.SUCCESS);
    }

    /** 全量替换 provider 绑定（owner/admin 或 key 创建者本人）：先删后建；null/空 = 清空；已吊销的 key 不可改绑定。 */
    @Transactional
    public AppKeyDto updateBindings(Long actorId, Long orgId, Long keyId, UpdateBindingsRequest req) {
        orgService.requireOrgRole(orgId, actorId, "owner", "admin", "member");
        AppKeyEntity k = requireOrgKey(orgId, keyId);
        requireCanManageKey(orgId, actorId, k);
        if (!"active".equals(k.getStatus())) {
            throw ApiException.validation("appkey 已吊销，不能修改绑定");
        }
        List<BindingRequest> normalized = normalizeBindings(orgId, req.bindings());
        bindings.deleteByAppKeyId(keyId);
        List<AppKeyProviderBindingEntity> saved = saveBindings(keyId, normalized);
        reconcileCloudRouteAfterBindingChange(k, saved);
        auditService.record(AuditModule.APPKEY, "update_appkey_bindings", actorId, orgId, "app_key",
                String.valueOf(keyId), "bindings=" + normalized.size(), AuditModule.SUCCESS);
        return toDto(k, saved, providerMapOf(List.of(saved)));
    }

    /**
     * 设置逻辑模型别名 {@code hub_cloud} 的默认路由。
     * 已吊销 key 不可改；目标 provider+model 必须在该 appkey 的绑定模型面内。
     */
    @Transactional
    public AppKeyDto updateCloudRoute(Long actorId, Long orgId, Long keyId, CloudRouteRequest req) {
        orgService.requireOrgRole(orgId, actorId, "owner", "admin", "member");
        AppKeyEntity k = requireOrgKey(orgId, keyId);
        requireCanManageKey(orgId, actorId, k);
        if (!"active".equals(k.getStatus())) {
            throw ApiException.validation("appkey 已吊销，不能修改 hub_cloud 路由");
        }
        List<AppKeyProviderBindingEntity> current = bindings.findByAppKeyId(keyId);
        applyCloudRoute(k, orgId, req, current);
        Map<Long, LlmProviderEntity> providerMap = providerMapOf(List.of(current));
        auditService.record(AuditModule.APPKEY, "update_appkey_cloud_route", actorId, orgId, "app_key",
                String.valueOf(keyId), "cloudRoute=" + k.getCloudProviderId() + ":" + k.getCloudModelName(),
                AuditModule.SUCCESS);
        return toDto(k, current, providerMap);
    }

    /** 清除逻辑模型别名 {@code hub_cloud} 的默认路由（显式停用该 key 的云端别名），幂等。 */
    @Transactional
    public AppKeyDto clearCloudRouteConfig(Long actorId, Long orgId, Long keyId) {
        orgService.requireOrgRole(orgId, actorId, "owner", "admin", "member");
        AppKeyEntity k = requireOrgKey(orgId, keyId);
        requireCanManageKey(orgId, actorId, k);
        List<AppKeyProviderBindingEntity> current = bindings.findByAppKeyId(keyId);
        if (k.getCloudProviderId() != null) {
            clearCloudRoute(k);
            appKeys.save(k);
            auditService.record(AuditModule.APPKEY, "clear_appkey_cloud_route", actorId, orgId, "app_key",
                    String.valueOf(keyId), "cloudRoute cleared", AuditModule.SUCCESS);
        }
        return toDto(k, current, providerMapOf(List.of(current)));
    }

    /** 取 key 并校验归属 org；不存在或跨 org 一律 404，不暴露存在性。 */
    private AppKeyEntity requireOrgKey(Long orgId, Long keyId) {
        AppKeyEntity k = appKeys.findById(keyId)
                .orElseThrow(() -> ApiException.notFound("appkey 不存在"));
        if (!orgId.equals(k.getOrgId())) {
            throw ApiException.notFound("appkey 不存在");
        }
        return k;
    }

    /**
     * key 级操作权限（appkey.self 语义）：创建者本人恒可管理自己的 key；
     * 非本人再要求 owner/admin（治理组织内全部，appkey.manage），否则 403。
     */
    private void requireCanManageKey(Long orgId, Long actorId, AppKeyEntity k) {
        if (actorId.equals(k.getCreatedBy())) {
            return;
        }
        orgService.requireOrgRole(orgId, actorId, "owner", "admin");
    }

    /**
     * 绑定校验与规整：provider 必须存在、active、且对当前 org 可见（平台共享池 org_id 为 null 或归属本 org，
     * 不可见按「不存在」报 400，防跨组织探测）；modelName 空白 → 空串（= 全部模型），
     * 非空白必须在 provider 声明的 models 清单内；同 (providerId, modelName) 去重。
     */
    private List<BindingRequest> normalizeBindings(Long orgId, List<BindingRequest> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<BindingRequest> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BindingRequest b : raw) {
            if (b == null || b.providerId() == null) {
                throw ApiException.validation("绑定缺少 providerId");
            }
            LlmProviderEntity p = providers.findById(b.providerId())
                    .orElseThrow(() -> ApiException.validation("provider 不存在：" + b.providerId()));
            if (p.getOrgId() != null && !p.getOrgId().equals(orgId)) {
                throw ApiException.validation("provider 不存在：" + b.providerId());
            }
            if (!"active".equals(p.getStatus())) {
                throw ApiException.validation("provider 已禁用：" + p.getSlug());
            }
            String model = b.modelName() == null ? "" : b.modelName().trim();
            if (!model.isEmpty()) {
                List<String> available = ProviderService.parseModels(p.getModels());
                if (!available.contains(model)) {
                    throw ApiException.validation("模型 " + model + " 不在 provider " + p.getSlug()
                            + " 可用模型内：" + String.join(",", available));
                }
            }
            if (seen.add(b.providerId() + " " + model)) {
                out.add(new BindingRequest(b.providerId(), model));
            }
        }
        return out;
    }

    private List<AppKeyProviderBindingEntity> saveBindings(Long appKeyId, List<BindingRequest> list) {
        List<AppKeyProviderBindingEntity> saved = new ArrayList<>();
        for (BindingRequest b : list) {
            AppKeyProviderBindingEntity e = new AppKeyProviderBindingEntity();
            e.setAppKeyId(appKeyId);
            e.setProviderId(b.providerId());
            e.setModelName(b.modelName() == null ? "" : b.modelName());
            saved.add(bindings.save(e));
        }
        return saved;
    }

    /**
     * 校验并落定 hub_cloud 路由：provider 必须存在、对 org 可见、active；modelName 非空且在 provider
     * 声明模型清单；且目标 provider+model 必须落在已保存的绑定模型面内（精确绑定，或“全部模型”绑定
     * 且模型在清单内）。调用方负责随后 appKeys.save（本方法只改内存实体）。
     */
    private void applyCloudRoute(AppKeyEntity k, Long orgId, CloudRouteRequest req,
                                 List<AppKeyProviderBindingEntity> currentBindings) {
        if (req == null || req.providerId() == null) {
            throw ApiException.validation("hub_cloud 路由缺少 providerId");
        }
        String model = req.modelName() == null ? "" : req.modelName().trim();
        if (model.isEmpty()) {
            throw ApiException.validation("hub_cloud 路由必须指定具体模型（不支持“全部模型”）");
        }
        LlmProviderEntity p = providers.findById(req.providerId())
                .orElseThrow(() -> ApiException.validation("provider 不存在：" + req.providerId()));
        if (p.getOrgId() != null && !p.getOrgId().equals(orgId)) {
            throw ApiException.validation("provider 不存在：" + req.providerId());
        }
        if (!"active".equals(p.getStatus())) {
            throw ApiException.validation("provider 已禁用：" + p.getSlug());
        }
        if (!ProviderService.parseModels(p.getModels()).contains(model)) {
            throw ApiException.validation("模型 " + model + " 不在 provider " + p.getSlug() + " 可用模型内");
        }
        boolean covered = currentBindings.stream().anyMatch(b ->
                p.getId().equals(b.getProviderId())
                        && (model.equals(b.getModelName())
                            || (b.getModelName() == null || b.getModelName().isEmpty())));
        if (!covered) {
            throw ApiException.validation("hub_cloud 路由目标必须先加入该 appkey 的绑定模型面："
                    + p.getSlug() + "/" + model);
        }
        k.setCloudProviderId(p.getId());
        k.setCloudModelName(model);
        appKeys.save(k);
    }

    /** 绑定全量替换后调和已配置路由：目标 provider+model 仍在新绑定面内则保留，否则清空（防悬空）。 */
    private void reconcileCloudRouteAfterBindingChange(AppKeyEntity k,
                                                       List<AppKeyProviderBindingEntity> newBindings) {
        if (k.getCloudProviderId() == null) {
            return;
        }
        boolean stillCovered = newBindings.stream().anyMatch(b ->
                k.getCloudProviderId().equals(b.getProviderId())
                        && (k.getCloudModelName().equals(b.getModelName())
                            || (b.getModelName() == null || b.getModelName().isEmpty())));
        if (!stillCovered) {
            clearCloudRoute(k);
        }
        appKeys.save(k);
    }

    private static void clearCloudRoute(AppKeyEntity k) {
        k.setCloudProviderId(null);
        k.setCloudModelName("");
    }

    /** 批量加载绑定涉及的 provider（不存在则缺席，展示层置 null 不炸）。 */
    private Map<Long, LlmProviderEntity> providerMapOf(Collection<List<AppKeyProviderBindingEntity>> grouped) {
        Set<Long> providerIds = new HashSet<>();
        for (List<AppKeyProviderBindingEntity> bs : grouped) {
            for (AppKeyProviderBindingEntity b : bs) {
                providerIds.add(b.getProviderId());
            }
        }
        if (providerIds.isEmpty()) {
            return Map.of();
        }
        return providers.findAllById(providerIds).stream()
                .collect(Collectors.toMap(LlmProviderEntity::getId, Function.identity()));
    }

    private AppKeyDto toDto(AppKeyEntity k, List<AppKeyProviderBindingEntity> bs,
                            Map<Long, LlmProviderEntity> providerMap) {
        List<AppKeyBindingDto> bindingDtos = bs.stream()
                .map(b -> {
                    LlmProviderEntity p = providerMap.get(b.getProviderId());
                    return new AppKeyBindingDto(b.getProviderId(), p == null ? null : p.getSlug(),
                            p == null ? null : p.getName(), b.getModelName());
                })
                .toList();
        AppKeyBindingDto cloudRoute = null;
        if (k.getCloudProviderId() != null) {
            LlmProviderEntity cp = providerMap.get(k.getCloudProviderId());
            cloudRoute = new AppKeyBindingDto(k.getCloudProviderId(), cp == null ? null : cp.getSlug(),
                    cp == null ? null : cp.getName(), k.getCloudModelName());
        }
        return new AppKeyDto(k.getId(), k.getOrgId(), k.getName(), k.getKeyPrefix(), k.getStatus(),
                k.getCreatedBy(), ldt(k.getCreatedAt()), ldt(k.getLastUsedAt()), ldt(k.getRevokedAt()),
                bindingDtos, cloudRoute);
    }

    /** 新 key：eck- 前缀 + 16 字节随机 hex（32 字符），总长 36。 */
    private static String newKeyString() {
        byte[] buf = new byte[16];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder("eck-");
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static LocalDateTime ldt(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
