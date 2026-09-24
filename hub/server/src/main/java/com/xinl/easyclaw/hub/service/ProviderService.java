package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.common.Slugger;
import com.xinl.easyclaw.hub.contract.provider.CreateProviderRequest;
import com.xinl.easyclaw.hub.contract.provider.ProviderDto;
import com.xinl.easyclaw.hub.contract.provider.UpdateProviderRequest;
import com.xinl.easyclaw.hub.entity.LlmProviderEntity;
import com.xinl.easyclaw.hub.entity.ProviderGrantEntity;
import com.xinl.easyclaw.hub.entity.MembershipEntity;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.AppKeyProviderBindingRepository;
import com.xinl.easyclaw.hub.repository.LlmProviderRepository;
import com.xinl.easyclaw.hub.repository.MembershipRepository;
import com.xinl.easyclaw.hub.repository.OrganizationRepository;
import com.xinl.easyclaw.hub.repository.ProviderGrantRepository;
import com.xinl.easyclaw.hub.repository.ProviderGrantUsageRepository;
import com.xinl.easyclaw.hub.repository.UserRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LLM provider：平台共享池（org_id 为 null，platformAdmin 维护）+ 组织自有 provider（owner/admin 维护）。
 * 列表可见范围 = 平台共享池 + 当前用户所在组织的 provider（platformAdmin 见全部）；
 * 真实 api key 只存 AES-GCM 密文（{@link CryptoService}），响应只回尾 4 位掩码 keyHint，永不回明文。
 * apiType = openai|anthropic（A1 网关转发按此分流）。组织归属创建后不可改。
 * 变更记审计（module=provider，组织级事件 orgId=归属组织，平台级事件 orgId=null）。
 */
@Service
public class ProviderService {

    /** 合法协议类型：openai = OpenAI 兼容；anthropic = Anthropic 原生。 */
    private static final Set<String> VALID_API_TYPES = Set.of("openai", "anthropic");

    private final LlmProviderRepository providers;
    private final AppKeyProviderBindingRepository bindings;
    private final ProviderGrantRepository providerGrants;
    private final ProviderGrantUsageRepository providerGrantUsages;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final OrganizationRepository orgs;
    private final OrgService orgService;
    private final CryptoService cryptoService;
    private final AuditService auditService;

    public ProviderService(LlmProviderRepository providers, AppKeyProviderBindingRepository bindings,
                           ProviderGrantRepository providerGrants, ProviderGrantUsageRepository providerGrantUsages,
                           UserRepository users, MembershipRepository memberships, OrganizationRepository orgs,
                           OrgService orgService, CryptoService cryptoService, AuditService auditService) {
        this.providers = providers;
        this.bindings = bindings;
        this.providerGrants = providerGrants;
        this.providerGrantUsages = providerGrantUsages;
        this.users = users;
        this.memberships = memberships;
        this.orgs = orgs;
        this.orgService = orgService;
        this.cryptoService = cryptoService;
        this.auditService = auditService;
    }

    /** provider 列表：platformAdmin 见全部；其余登录用户见 平台共享池 + 所在组织 的 provider。 */
    @Transactional(readOnly = true)
    public List<ProviderDto> listForUser(Long actorId) {
        if (isPlatformAdmin(actorId)) {
            return providers.findAll(Sort.by("id")).stream().map(this::toDto).toList();
        }
        List<Long> orgIds = memberships.findByUserId(actorId).stream()
                .map(MembershipEntity::getOrgId)
                .toList();
        return providers.findByOrgIdIsNullOrOrgIdInOrderById(orgIds).stream().map(this::toDto).toList();
    }

    /**
     * 创建 provider：orgId 省略 = 平台共享池（仅 platformAdmin）；否则归属组织（platformAdmin 或该组织 owner/admin）。
     * slug 作用域内唯一；apiKey 加密落库；apiType 省略默认 openai。
     */
    @Transactional
    public ProviderDto create(Long actorId, CreateProviderRequest req) {
        Long orgId = req.orgId();
        if (orgId != null && !orgs.existsById(orgId)) {
            throw ApiException.validation("组织不存在：" + orgId);
        }
        requireManageScope(actorId, orgId);
        String slug = req.slug().trim();
        if (Slugger.isValid(slug)) {
            throw ApiException.validation("slug 只能包含小写字母/数字/连字符");
        }
        boolean slugTaken = orgId == null
                ? providers.existsByOrgIdIsNullAndSlug(slug)
                : providers.existsByOrgIdAndSlug(orgId, slug);
        if (slugTaken) {
            throw ApiException.conflict("provider slug 已存在：" + slug);
        }
        String apiType = normalizeApiType(req.apiType());
        LlmProviderEntity p = new LlmProviderEntity();
        p.setOrgId(orgId);
        p.setSlug(slug);
        p.setName(req.name().trim());
        p.setBaseUrl(req.baseUrl().trim());
        p.setApiKeyCiphertext(cryptoService.encrypt(req.apiKey().trim()));
        p.setModels(joinModels(req.models()));
        p.setApiType(apiType);
        p.setRemark(req.remark());
        providers.save(p);
        auditService.record(AuditModule.PROVIDER, "create_provider", actorId, orgId, "provider",
                String.valueOf(p.getId()), "slug=" + slug + ",apiType=" + apiType, AuditModule.SUCCESS);
        return toDto(p);
    }

    /** 更新 provider：字段给了才改；apiKey 给了才换；status 仅 active|disabled；组织归属不可改。 */
    @Transactional
    public ProviderDto update(Long actorId, Long id, UpdateProviderRequest req) {
        LlmProviderEntity p = providers.findById(id)
                .orElseThrow(() -> ApiException.notFound("provider 不存在"));
        requireManageScope(actorId, p.getOrgId());
        if (req.name() != null && !req.name().isBlank()) {
            p.setName(req.name().trim());
        }
        if (req.baseUrl() != null && !req.baseUrl().isBlank()) {
            p.setBaseUrl(req.baseUrl().trim());
        }
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            p.setApiKeyCiphertext(cryptoService.encrypt(req.apiKey().trim()));
        }
        if (req.models() != null) {
            p.setModels(joinModels(req.models()));
        }
        if (req.apiType() != null && !req.apiType().isBlank()) {
            p.setApiType(normalizeApiType(req.apiType()));
        }
        if (req.status() != null && !req.status().isBlank()) {
            String status = req.status().trim();
            if (!"active".equals(status) && !"disabled".equals(status)) {
                throw ApiException.validation("非法状态：" + status);
            }
            p.setStatus(status);
        }
        if (req.remark() != null) {
            p.setRemark(req.remark());
        }
        providers.save(p);
        auditService.record(AuditModule.PROVIDER, "update_provider", actorId, p.getOrgId(), "provider",
                String.valueOf(p.getId()), "slug=" + p.getSlug(), AuditModule.SUCCESS);
        return toDto(p);
    }

    /** 删除 provider：仍被 appkey 绑定时拒绝（409），先解绑再删；物理删除，连带清理授权与用量计数。 */
    @Transactional
    public void delete(Long actorId, Long id) {
        LlmProviderEntity p = providers.findById(id)
                .orElseThrow(() -> ApiException.notFound("provider 不存在"));
        requireManageScope(actorId, p.getOrgId());
        if (bindings.existsByProviderId(id)) {
            throw ApiException.conflict("该 provider 仍被 appkey 绑定，请先解绑");
        }
        List<Long> grantIds = providerGrants.findByProviderIdOrderByIdAsc(id).stream()
                .map(ProviderGrantEntity::getId).toList();
        providerGrants.deleteByProviderId(id);
        grantIds.forEach(providerGrantUsages::deleteByGrantId);
        providers.delete(p);
        auditService.record(AuditModule.PROVIDER, "delete_provider", actorId, p.getOrgId(), "provider",
                String.valueOf(id), "slug=" + p.getSlug(), AuditModule.SUCCESS);
    }

    /**
     * 管理范围门禁：platformAdmin 可管一切；orgId 非空时该组织 owner/admin 可管；
     * orgId 为 null（平台共享池）仅 platformAdmin。组织不存在/非成员由 roleOf 判 null 自然 403。
     * 包级可见：ProviderGrantService（provider 授权管理）复用同一判定。
     */
    void requireManageScope(Long actorId, Long orgId) {
        if (isPlatformAdmin(actorId)) {
            return;
        }
        if (orgId == null) {
            throw ApiException.forbidden("仅平台管理员可管理平台共享 provider");
        }
        String role = orgService.roleOf(orgId, actorId);
        if (!"owner".equals(role) && !"admin".equals(role)) {
            throw ApiException.forbidden("仅组织 owner/admin 可管理本组织 provider");
        }
    }

    private boolean isPlatformAdmin(Long userId) {
        return users.findById(userId).map(UserEntity::isPlatformAdmin).orElse(false);
    }

    private static String normalizeApiType(String apiType) {
        String t = apiType == null || apiType.isBlank() ? "openai" : apiType.trim();
        if (!VALID_API_TYPES.contains(t)) {
            throw ApiException.validation("非法 apiType：" + t + "（仅支持 openai|anthropic）");
        }
        return t;
    }

    /** 模型清单 → 逗号分隔串（trim、去空项）；null/空列表 → 空串。 */
    static String joinModels(List<String> models) {
        if (models == null || models.isEmpty()) {
            return "";
        }
        return models.stream().map(String::trim).filter(s -> !s.isEmpty())
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    private ProviderDto toDto(LlmProviderEntity p) {
        // 组织名随行带出供列表展示；同一事务内重复查同一 org 命中 Hibernate 一级缓存，量小不批量化。
        String orgName = p.getOrgId() == null ? null
                : orgs.findById(p.getOrgId()).map(o -> o.getName()).orElse(null);
        return new ProviderDto(p.getId(), p.getSlug(), p.getName(), p.getBaseUrl(), parseModels(p.getModels()),
                p.getStatus(), p.getRemark(), keyHint(p), p.getOrgId(), orgName, p.getApiType());
    }

    /** 尾 4 位掩码（如 ****a1b2）；解密失败（主密钥不符/密文损坏）返回 null，不炸列表。 */
    private String keyHint(LlmProviderEntity p) {
        try {
            String plain = cryptoService.decrypt(p.getApiKeyCiphertext());
            return plain.length() <= 4 ? "****" : "****" + plain.substring(plain.length() - 4);
        } catch (Exception e) {
            return null;
        }
    }

    /** 逗号分隔模型清单 → 列表（trim、去空项）；空串 → 空列表。包私有供 AppKeyService 校验复用。 */
    public static List<String> parseModels(String models) {
        if (models == null || models.isBlank()) {
            return List.of();
        }
        return Arrays.stream(models.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
