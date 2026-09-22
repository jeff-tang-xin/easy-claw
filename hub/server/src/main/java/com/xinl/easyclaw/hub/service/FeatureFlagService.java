package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.common.Slugger;
import com.xinl.easyclaw.hub.contract.featureflag.CreateFeatureFlagRequest;
import com.xinl.easyclaw.hub.contract.featureflag.FeatureFlagDto;
import com.xinl.easyclaw.hub.contract.featureflag.OrgFlagSettingDto;
import com.xinl.easyclaw.hub.contract.featureflag.UpdateFeatureFlagRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeFlagInfo;
import com.xinl.easyclaw.hub.entity.FeatureFlagEntity;
import com.xinl.easyclaw.hub.entity.OrgFlagSettingEntity;
import com.xinl.easyclaw.hub.repository.FeatureFlagRepository;
import com.xinl.easyclaw.hub.repository.OrgFlagSettingRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台功能开关目录 + 组织启用开关：
 * <ul>
 *   <li>目录（feature_flags）为平台级内置：CRUD 仅 platformAdmin（{@link PlatformAdminGuard} 裁决）；</li>
 *   <li>组织侧（org_flag_settings）只决定「是否启用」：惰性行，无行 = 默认启用，owner/admin 写、成员读；</li>
 *   <li>生效语义（唯一裁决口径）= 平台 enabled AND 组织 enabled，spoke 下发按此过滤。</li>
 * </ul>
 */
@Service
public class FeatureFlagService {

    private final FeatureFlagRepository flags;
    private final OrgFlagSettingRepository settings;
    private final OrgService orgService;
    private final AuditService auditService;
    private final PlatformAdminGuard platformAdmin;

    public FeatureFlagService(FeatureFlagRepository flags, OrgFlagSettingRepository settings,
                              OrgService orgService, AuditService auditService, PlatformAdminGuard platformAdmin) {
        this.flags = flags;
        this.settings = settings;
        this.orgService = orgService;
        this.auditService = auditService;
        this.platformAdmin = platformAdmin;
    }

    // ---------- 平台目录（platformAdmin） ----------

    /** 平台开关目录（按 sort_order,id）。 */
    @Transactional(readOnly = true)
    public List<FeatureFlagDto> listCatalog(Long requesterId) {
        platformAdmin.require(requesterId);
        return flags.findAllByOrderBySortOrderAscIdAsc().stream().map(this::toDto).toList();
    }

    @Transactional
    public FeatureFlagDto createCatalogItem(Long requesterId, CreateFeatureFlagRequest req) {
        platformAdmin.require(requesterId);
        String key = resolveKey(req.flagKey(), req.label());
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.setFlagKey(key);
        f.setLabel(req.label().trim());
        f.setDescription(blankToEmpty(req.description()));
        f.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        f.setEnabled(req.enabled() == null || req.enabled());
        flags.save(f);
        auditService.record(AuditModule.FEATURE_FLAG, "create_platform_flag", requesterId, null, "feature_flag",
                String.valueOf(f.getId()), "key=" + f.getFlagKey(), AuditModule.SUCCESS);
        return toDto(f);
    }

    /** 部分更新；flagKey 创建后不可改（服务端裁决，请求体不含该字段）。 */
    @Transactional
    public FeatureFlagDto updateCatalogItem(Long requesterId, Long id, UpdateFeatureFlagRequest req) {
        platformAdmin.require(requesterId);
        FeatureFlagEntity f = requireFlag(id);
        if (req.label() != null && !req.label().isBlank()) {
            f.setLabel(req.label().trim());
        }
        if (req.description() != null) {
            f.setDescription(blankToEmpty(req.description()));
        }
        if (req.sortOrder() != null) {
            f.setSortOrder(req.sortOrder());
        }
        if (req.enabled() != null) {
            f.setEnabled(req.enabled());
        }
        flags.save(f);
        auditService.record(AuditModule.FEATURE_FLAG, "update_platform_flag", requesterId, null, "feature_flag",
                String.valueOf(f.getId()), "key=" + f.getFlagKey(), AuditModule.SUCCESS);
        return toDto(f);
    }

    /** 删除平台开关，级联删除各组织的启用开关行。 */
    @Transactional
    public void deleteCatalogItem(Long requesterId, Long id) {
        platformAdmin.require(requesterId);
        FeatureFlagEntity f = requireFlag(id);
        flags.delete(f);
        settings.deleteAll(settings.findByFlagIdIn(List.of(id)));
        auditService.record(AuditModule.FEATURE_FLAG, "delete_platform_flag", requesterId, null, "feature_flag",
                String.valueOf(f.getId()), "key=" + f.getFlagKey(), AuditModule.SUCCESS);
    }

    // ---------- 组织启用开关（读=成员，写=owner/admin） ----------

    /** 组织开关启用态：全量目录 × 该组织生效态（无开关行 = 默认启用）。 */
    @Transactional(readOnly = true)
    public List<OrgFlagSettingDto> listSettings(Long requesterId, Long orgId) {
        requireMember(orgId, requesterId);
        Map<Long, Boolean> enabledByFlag = new HashMap<>();
        for (OrgFlagSettingEntity s : settings.findByOrgId(orgId)) {
            enabledByFlag.put(s.getFlagId(), s.getEnabled());
        }
        List<OrgFlagSettingDto> out = new ArrayList<>();
        for (FeatureFlagEntity f : flags.findAllByOrderBySortOrderAscIdAsc()) {
            out.add(new OrgFlagSettingDto(f.getId(), f.getFlagKey(), f.getLabel(),
                    enabledByFlag.getOrDefault(f.getId(), true)));
        }
        return out;
    }

    /** 幂等 upsert 组织启用行；flagId 不存在 → 404。 */
    @Transactional
    public void setEnabled(Long requesterId, Long orgId, Long flagId, boolean enabled) {
        requireWriter(orgId, requesterId);
        requireFlag(flagId);
        OrgFlagSettingEntity s = settings.findByOrgIdAndFlagId(orgId, flagId).orElseGet(() -> {
            OrgFlagSettingEntity n = new OrgFlagSettingEntity();
            n.setOrgId(orgId);
            n.setFlagId(flagId);
            return n;
        });
        s.setEnabled(enabled);
        settings.save(s);
        auditService.record(AuditModule.FEATURE_FLAG, "set_org_flag_enabled", requesterId, orgId, "feature_flag",
                String.valueOf(flagId), "enabled=" + enabled, AuditModule.SUCCESS);
    }

    // ---------- 下发用（供 SpokeService 复用，只读，无请求者上下文） ----------

    /**
     * 某组织的生效开关列表（GET /api/spoke/feature-flags）：仅平台总开关开启的条目，
     * enabled = 平台 enabled AND 组织 enabled。
     */
    @Transactional(readOnly = true)
    public List<SpokeFlagInfo> effectiveFlags(Long orgId) {
        Map<Long, Boolean> enabledByFlag = new HashMap<>();
        for (OrgFlagSettingEntity s : settings.findByOrgId(orgId)) {
            enabledByFlag.put(s.getFlagId(), s.getEnabled());
        }
        List<SpokeFlagInfo> out = new ArrayList<>();
        for (FeatureFlagEntity f : flags.findAllByOrderBySortOrderAscIdAsc()) {
            if (Boolean.TRUE.equals(f.getEnabled())) {
                boolean orgEnabled = enabledByFlag.getOrDefault(f.getId(), true);
                out.add(new SpokeFlagInfo(f.getFlagKey(), f.getLabel(), orgEnabled));
            }
        }
        return out;
    }

    // ---------- 鉴权 ----------

    private void requireMember(Long orgId, Long userId) {
        if (orgService.roleOf(orgId, userId) == null) {
            throw ApiException.forbidden("非组织成员");
        }
    }

    private void requireWriter(Long orgId, Long userId) {
        orgService.requireOrgRole(orgId, userId, "owner", "admin");
    }

    // ---------- 内部 ----------

    private FeatureFlagEntity requireFlag(Long id) {
        return flags.findById(id).orElseThrow(() -> ApiException.notFound("功能开关不存在"));
    }

    /** flagKey 留空则由 label 派生（纯中文兜底 "flag"），冲突自动追加 -2/-3…。 */
    private String resolveKey(String requested, String label) {
        if (requested != null && !requested.isBlank()) {
            String key = requested.trim();
            if (flags.existsByFlagKey(key)) {
                throw ApiException.conflict("flagKey 已存在");
            }
            return key;
        }
        String base = Slugger.derive(label, "flag");
        String candidate = base;
        for (int i = 2; flags.existsByFlagKey(candidate); i++) {
            candidate = base + "-" + i;
        }
        return candidate;
    }

    private FeatureFlagDto toDto(FeatureFlagEntity f) {
        return new FeatureFlagDto(f.getId(), f.getFlagKey(), f.getLabel(), f.getDescription(), f.getEnabled(),
                f.getSortOrder());
    }

    private static String blankToEmpty(String s) {
        return s == null || s.isBlank() ? "" : s.trim();
    }
}
