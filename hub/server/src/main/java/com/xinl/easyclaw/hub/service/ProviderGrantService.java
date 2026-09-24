package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.contract.provider.AddProviderCreditsRequest;
import com.xinl.easyclaw.hub.contract.provider.CreateProviderGrantRequest;
import com.xinl.easyclaw.hub.contract.provider.CreditBalanceDto;
import com.xinl.easyclaw.hub.contract.provider.CreditGrantSummaryDto;
import com.xinl.easyclaw.hub.contract.provider.CreditUsageDto;
import com.xinl.easyclaw.hub.contract.provider.ProviderCreditDto;
import com.xinl.easyclaw.hub.contract.provider.ProviderGrantDto;
import com.xinl.easyclaw.hub.contract.provider.UpdateGrantPlanRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeCreditInfo;
import com.xinl.easyclaw.hub.entity.CreditConsumptionEntity;
import com.xinl.easyclaw.hub.entity.LlmProviderEntity;
import com.xinl.easyclaw.hub.entity.ModelCatalogEntity;
import com.xinl.easyclaw.hub.entity.ProviderCreditEntity;
import com.xinl.easyclaw.hub.entity.ProviderGrantEntity;
import com.xinl.easyclaw.hub.entity.ProviderGrantUsageEntity;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.CreditConsumptionRepository;
import com.xinl.easyclaw.hub.repository.LlmProviderRepository;
import com.xinl.easyclaw.hub.repository.ModelCatalogRepository;
import com.xinl.easyclaw.hub.repository.ProviderCreditRepository;
import com.xinl.easyclaw.hub.repository.ProviderGrantRepository;
import com.xinl.easyclaw.hub.repository.ProviderGrantUsageRepository;
import com.xinl.easyclaw.hub.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider 授权：按用户授权 provider（appkey 创建者维度），授权可选每日调用次数上限
 * （NULL = 不限流）与有效期（NULL = 永久）。
 * 启用语义：provider 存在任意授权行 = 启用授权模式（仅清单内用户可用）；
 * 未配置任何授权行 = 不启用（保持开放），存量 appkey 不受影响。
 * 管理权限与 provider 一致（{@link ProviderService#requireManageScope}）：
 * 平台池仅 platformAdmin，组织 provider 由 owner/admin 管理。
 * V27 积分池：授权行可配周期发放计划（每日/每月/每年，NULL = 不发放该周期），
 * 管理员可手动发放临时积分；请求消耗按过期时间 FIFO 扣减，余额不足 429。
 */
@Service
public class ProviderGrantService {

    /** 积分面额/比例下限（1 位小数的最小正值）。 */
    private static final BigDecimal CREDIT_MIN = new BigDecimal("0.1");

    private final ProviderGrantRepository grants;
    private final ProviderGrantUsageRepository usages;
    private final ProviderCreditRepository credits;
    private final CreditConsumptionRepository consumptions;
    private final ModelCatalogRepository modelCatalog;
    private final LlmProviderRepository providers;
    private final UserRepository users;
    private final ProviderService providerService;
    private final OrgService orgService;
    private final AuditService auditService;

    public ProviderGrantService(ProviderGrantRepository grants, ProviderGrantUsageRepository usages,
                                ProviderCreditRepository credits, CreditConsumptionRepository consumptions,
                                ModelCatalogRepository modelCatalog, LlmProviderRepository providers,
                                UserRepository users, ProviderService providerService, OrgService orgService,
                                AuditService auditService) {
        this.grants = grants;
        this.usages = usages;
        this.credits = credits;
        this.consumptions = consumptions;
        this.modelCatalog = modelCatalog;
        this.providers = providers;
        this.users = users;
        this.providerService = providerService;
        this.orgService = orgService;
        this.auditService = auditService;
    }

    // ==================== 管理端 ====================

    @Transactional(readOnly = true)
    public List<ProviderGrantDto> list(Long actorId, Long providerId) {
        LlmProviderEntity p = requireManageableProvider(actorId, providerId);
        List<ProviderGrantEntity> rows = grants.findByProviderIdOrderByIdAsc(p.getId());
        Map<Long, UserEntity> userMap = users.findAllById(
                        rows.stream().map(ProviderGrantEntity::getUserId).toList()).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        LocalDate today = LocalDate.now();
        return rows.stream()
                .map(g -> toDto(g, userMap.get(g.getUserId()), today))
                .toList();
    }

    /** 配置授权：(provider, user) 唯一；dailyLimit 非空须 ≥1；expiresAt 非空须在未来。 */
    @Transactional
    public ProviderGrantDto create(Long actorId, Long providerId, CreateProviderGrantRequest req) {
        LlmProviderEntity p = requireManageableProvider(actorId, providerId);
        UserEntity user = users.findById(req.userId())
                .orElseThrow(() -> ApiException.validation("用户不存在：" + req.userId()));
        if (req.dailyLimit() != null && req.dailyLimit() < 1) {
            throw ApiException.validation("每日调用次数上限须 ≥1（不限流请留空）");
        }
        if (req.expiresAt() != null && req.expiresAt().isBefore(Instant.now())) {
            throw ApiException.validation("授权有效期须晚于当前时间（永久授权请留空）");
        }
        if (grants.findByProviderIdAndUserId(p.getId(), user.getId()).isPresent()) {
            throw ApiException.conflict("该用户已在此 provider 的授权清单中");
        }
        ProviderGrantEntity g = new ProviderGrantEntity();
        g.setProviderId(p.getId());
        g.setUserId(user.getId());
        g.setDailyLimit(req.dailyLimit());
        g.setExpiresAt(req.expiresAt());
        g.setCreatedBy(actorId);
        grants.save(g);
        auditService.record(AuditModule.PROVIDER, "create_provider_grant", actorId, p.getOrgId(), "provider",
                String.valueOf(p.getId()),
                "userId=" + user.getId() + ",dailyLimit=" + req.dailyLimit() + ",expiresAt=" + req.expiresAt(),
                AuditModule.SUCCESS);
        return toDto(g, user, LocalDate.now());
    }

    /** 取消授权：连带清理用量计数行。 */
    @Transactional
    public void delete(Long actorId, Long providerId, Long grantId) {
        LlmProviderEntity p = requireManageableProvider(actorId, providerId);
        ProviderGrantEntity g = grants.findById(grantId)
                .orElseThrow(() -> ApiException.notFound("授权不存在"));
        if (!p.getId().equals(g.getProviderId())) {
            throw ApiException.notFound("授权不存在");
        }
        grants.delete(g);
        usages.deleteByGrantId(grantId);
        credits.deleteByGrantId(grantId);
        auditService.record(AuditModule.PROVIDER, "delete_provider_grant", actorId, p.getOrgId(), "provider",
                String.valueOf(p.getId()), "grantId=" + grantId + ",userId=" + g.getUserId(), AuditModule.SUCCESS);
    }

    // ==================== 网关校验（热路径） ====================

    /**
     * 校验 appkey 创建者对 provider 的授权并计数。返回 null = 放行；
     * 否则返回拒绝原因（status/type/code/message 由网关转 OpenAI 兼容错误）。
     * 判定顺序：未启用授权 → 放行；无授权行/已过期 → 403；超每日次数 → 429；
     * 积分池启用且余额不足 → 429（与每日次数限流独立叠加）。
     * 积分扣减按请求模型的目录比例计价（{@link #modelCost}，未登记模型默认 1）。
     * 事务注在本方法上（网关主流程无事务，这里是独立短事务）：
     * {@link #consumeOnce} 为同类自调用，Spring 代理不拦截，事务须由本方法提供。
     */
    @Transactional
    public GrantCheck checkAndConsume(Long providerId, Long appKeyCreatorId, String modelName) {
        if (!grants.existsByProviderId(providerId)) {
            return null;
        }
        ProviderGrantEntity g = grants.findByProviderIdAndUserId(providerId, appKeyCreatorId).orElse(null);
        if (g == null) {
            return new GrantCheck(403, "permission_error", "provider_not_authorized",
                    "appkey 创建者未被授权使用该 provider");
        }
        if (g.getExpiresAt() != null && g.getExpiresAt().isBefore(Instant.now())) {
            return new GrantCheck(403, "permission_error", "provider_grant_expired",
                    "对该 provider 的授权已过期");
        }
        if (g.getDailyLimit() != null) {
            int used = consumeOnce(g.getId(), LocalDate.now());
            if (used > g.getDailyLimit()) {
                return new GrantCheck(429, "rate_limit_error", "provider_daily_limit_exceeded",
                        "该 provider 今日调用次数已用尽（" + g.getDailyLimit() + " 次/日）");
            }
        }
        if (creditsEnabled(g)) {
            issuePeriodCredits(g);
            BigDecimal cost = modelCost(modelName);
            if (!consumeCredits(g.getId(), cost)) {
                return new GrantCheck(429, "rate_limit_error", "provider_credits_exhausted",
                        "该 provider 的积分余额不足（本次模型 " + modelName + " 需 " + cost + " 积分）");
            }
            recordConsumption(g, providerId, modelName, cost);
        }
        return null;
    }

    /**
     * 计数自增：upsert 原子自增后读回新值。事务由 {@link #checkAndConsume} 提供
     * （同类自调用，本方法上的事务注解不会生效）。
     * 只为配置了 daily_limit 的授权行计数；不限流的授权不落计数行。
     */
    public int consumeOnce(Long grantId, LocalDate today) {
        usages.upsertIncrement(grantId, today);
        return usages.findByGrantIdAndUsageDate(grantId, today)
                .map(ProviderGrantUsageEntity::getRequestCount)
                .orElse(1);
    }

    /** 网关拒绝结果：status/type/code 对齐 {@code GatewayException} 语义。 */
    public record GrantCheck(int status, String type, String code, String message) {
    }

    // ==================== 积分池管理端 ====================

    /**
     * 更新授权的周期积分发放计划（每日/每月/每年，NULL = 不发放该周期）。
     * 修改只影响后续周期发放，已发放的当期积分不回溯调整。
     */
    @Transactional
    public ProviderGrantDto updatePlan(Long actorId, Long providerId, Long grantId, UpdateGrantPlanRequest req) {
        LlmProviderEntity p = requireManageableProvider(actorId, providerId);
        ProviderGrantEntity g = requireGrantOfProvider(p, grantId);
        g.setDailyCredits(req.dailyCredits());
        g.setMonthlyCredits(req.monthlyCredits());
        g.setYearlyCredits(req.yearlyCredits());
        grants.save(g);
        auditService.record(AuditModule.PROVIDER, "update_grant_credit_plan", actorId, p.getOrgId(), "provider",
                String.valueOf(p.getId()),
                "grantId=" + grantId + ",daily=" + req.dailyCredits() + ",monthly=" + req.monthlyCredits()
                        + ",yearly=" + req.yearlyCredits(),
                AuditModule.SUCCESS);
        return toDto(g, users.findById(g.getUserId()).orElse(null), LocalDate.now());
    }

    /** 手动发放临时积分：面额 ≥0.1、有效期必填且须在未来；与周期积分同池 FIFO 消耗。 */
    @Transactional
    public ProviderCreditDto addTempCredits(Long actorId, Long providerId, Long grantId,
                                            AddProviderCreditsRequest req) {
        LlmProviderEntity p = requireManageableProvider(actorId, providerId);
        ProviderGrantEntity g = requireGrantOfProvider(p, grantId);
        if (req.credits() == null || req.credits().compareTo(CREDIT_MIN) < 0) {
            throw ApiException.validation("积分面额须 ≥0.1");
        }
        if (req.expiresAt() == null || req.expiresAt().isBefore(Instant.now())) {
            throw ApiException.validation("积分有效期须晚于当前时间");
        }
        ProviderCreditEntity c = new ProviderCreditEntity();
        c.setGrantId(g.getId());
        c.setPeriodType("temp");
        c.setPeriodKey(null);
        c.setCredits(truncate1(req.credits()));
        c.setConsumed(BigDecimal.ZERO);
        c.setExpiresAt(req.expiresAt());
        credits.save(c);
        auditService.record(AuditModule.PROVIDER, "add_provider_credits", actorId, p.getOrgId(), "provider",
                String.valueOf(p.getId()),
                "grantId=" + grantId + ",credits=" + c.getCredits() + ",expiresAt=" + req.expiresAt(),
                AuditModule.SUCCESS);
        return toCreditDto(c);
    }

    /** 积分流水（管理端）：含已过期/已耗尽行，按发放时间倒序。 */
    @Transactional(readOnly = true)
    public List<ProviderCreditDto> listCreditRows(Long actorId, Long providerId, Long grantId) {
        LlmProviderEntity p = requireManageableProvider(actorId, providerId);
        requireGrantOfProvider(p, grantId);
        return credits.findByGrantIdOrderByIdDesc(grantId).stream().map(this::toCreditDto).toList();
    }

    // ==================== spoke 端（appkey 创建者维度） ====================

    /**
     * appkey 创建者的积分视图：按其每条授权一行（provider 维度）。
     * remaining = 积分池可用余额（未启用积分池为 null）；dailyLimit/usedToday 同管理端口径。
     */
    @Transactional(readOnly = true)
    public List<SpokeCreditInfo> myCredits(Long userId) {
        LocalDate today = LocalDate.now();
        return grants.findByUserIdOrderByIdAsc(userId).stream()
                .map(g -> {
                    LlmProviderEntity p = providers.findById(g.getProviderId()).orElse(null);
                    Integer usedToday = g.getDailyLimit() == null ? null
                            : usages.findByGrantIdAndUsageDate(g.getId(), today)
                                    .map(ProviderGrantUsageEntity::getRequestCount).orElse(0);
                    return new SpokeCreditInfo(g.getProviderId(),
                            p == null ? null : p.getName(), p == null ? null : p.getSlug(),
                            creditsEnabled(g) ? remainingOf(g.getId()) : null,
                            g.getDailyLimit(), usedToday);
                })
                .toList();
    }

    // ==================== 积分使用情况页面（hub 前端） ====================

    /**
     * 我的积分余额（含构成）：按当前用户每条授权一行（provider 维度）。
     * remaining = 积分池总剩余；四项构成为各周期未过期剩余（未启用积分池全 null）。
     */
    @Transactional(readOnly = true)
    public List<CreditBalanceDto> myCreditBalances(Long userId) {
        LocalDate today = LocalDate.now();
        return grants.findByUserIdOrderByIdAsc(userId).stream()
                .map(g -> {
                    LlmProviderEntity p = providers.findById(g.getProviderId()).orElse(null);
                    Map<String, BigDecimal> comp = compositionOf(g.getId());
                    boolean enabled = creditsEnabled(g);
                    BigDecimal total = comp.values().stream()
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    Integer usedToday = g.getDailyLimit() == null ? null
                            : usages.findByGrantIdAndUsageDate(g.getId(), today)
                                    .map(ProviderGrantUsageEntity::getRequestCount).orElse(0);
                    return new CreditBalanceDto(g.getProviderId(),
                            p == null ? null : p.getName(), p == null ? null : p.getSlug(),
                            enabled ? total : null,
                            enabled ? comp.get("daily") : null,
                            enabled ? comp.get("monthly") : null,
                            enabled ? comp.get("yearly") : null,
                            enabled ? comp.get("temp") : null,
                            g.getDailyLimit(), usedToday);
                })
                .toList();
    }

    /** 我的使用记录：当前用户全部授权的消耗明细（模型、消耗分值），按时间倒序分页。 */
    @Transactional(readOnly = true)
    public List<CreditUsageDto> myCreditUsages(Long userId, int page, int size) {
        List<ProviderGrantEntity> myGrants = grants.findByUserIdOrderByIdAsc(userId);
        if (myGrants.isEmpty()) {
            return List.of();
        }
        List<Long> grantIds = myGrants.stream().map(ProviderGrantEntity::getId).toList();
        Map<Long, LlmProviderEntity> providerMap = providers.findAllById(
                        myGrants.stream().map(ProviderGrantEntity::getProviderId).toList()).stream()
                .collect(Collectors.toMap(LlmProviderEntity::getId, Function.identity()));
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        return consumptions.findByGrantIdInOrderByIdDesc(grantIds, pageable).stream()
                .map(c -> {
                    LlmProviderEntity p = providerMap.get(c.getProviderId());
                    return new CreditUsageDto(c.getId(), c.getProviderId(),
                            p == null ? null : p.getName(), p == null ? null : p.getSlug(),
                            c.getModelName(), c.getCost(), c.getCreatedAt());
                })
                .toList();
    }

    /**
     * 组织积分总览（owner/admin）：组织内全部 provider 的所有授权行，每行 = provider × 用户。
     * 平台共享池 provider 不在本组织范围内（由 platformCreditOverview 单独查看）。
     */
    @Transactional(readOnly = true)
    public List<CreditGrantSummaryDto> orgCreditOverview(Long actorId, Long orgId) {
        orgService.requireOrgRole(orgId, actorId, "owner", "admin");
        return creditOverview(providers.findByOrgIdOrderByIdAsc(orgId));
    }

    /** 平台共享池积分总览（platformAdmin）：orgId 为 NULL 的 provider 全部授权行。 */
    @Transactional(readOnly = true)
    public List<CreditGrantSummaryDto> platformCreditOverview(Long actorId) {
        providerService.requireManageScope(actorId, null);
        return creditOverview(providers.findByOrgIdIsNullOrderByIdAsc());
    }

    private List<CreditGrantSummaryDto> creditOverview(List<LlmProviderEntity> providerRows) {
        if (providerRows.isEmpty()) {
            return List.of();
        }
        Map<Long, LlmProviderEntity> providerMap = providerRows.stream()
                .collect(Collectors.toMap(LlmProviderEntity::getId, Function.identity()));
        List<ProviderGrantEntity> rows = grants.findByProviderIdInOrderByIdAsc(providerMap.keySet());
        Map<Long, UserEntity> userMap = users.findAllById(
                        rows.stream().map(ProviderGrantEntity::getUserId).toList()).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        LocalDate today = LocalDate.now();
        return rows.stream()
                .map(g -> {
                    LlmProviderEntity p = providerMap.get(g.getProviderId());
                    Map<String, BigDecimal> comp = compositionOf(g.getId());
                    boolean enabled = creditsEnabled(g);
                    BigDecimal total = comp.values().stream()
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    Integer usedToday = g.getDailyLimit() == null ? null
                            : usages.findByGrantIdAndUsageDate(g.getId(), today)
                                    .map(ProviderGrantUsageEntity::getRequestCount).orElse(0);
                    UserEntity u = userMap.get(g.getUserId());
                    return new CreditGrantSummaryDto(g.getId(), g.getProviderId(),
                            p == null ? null : p.getName(), p == null ? null : p.getSlug(),
                            g.getUserId(), u == null ? null : u.getUsername(),
                            enabled ? total : null,
                            enabled ? comp.get("daily") : null,
                            enabled ? comp.get("monthly") : null,
                            enabled ? comp.get("yearly") : null,
                            enabled ? comp.get("temp") : null,
                            g.getDailyLimit(), usedToday);
                })
                .toList();
    }

    // ==================== 内部 ====================

    private LlmProviderEntity requireManageableProvider(Long actorId, Long providerId) {
        LlmProviderEntity p = providers.findById(providerId)
                .orElseThrow(() -> ApiException.notFound("provider 不存在"));
        providerService.requireManageScope(actorId, p.getOrgId());
        return p;
    }

    /** 取 provider 名下的授权行（id 归属不符按不存在处理，防跨 provider 越权）。 */
    private ProviderGrantEntity requireGrantOfProvider(LlmProviderEntity p, Long grantId) {
        ProviderGrantEntity g = grants.findById(grantId)
                .orElseThrow(() -> ApiException.notFound("授权不存在"));
        if (!p.getId().equals(g.getProviderId())) {
            throw ApiException.notFound("授权不存在");
        }
        return g;
    }

    /** 积分池是否启用：发放计划任一非空，或已存在积分行（计划全关但临时积分仍在有效期内）。 */
    private boolean creditsEnabled(ProviderGrantEntity g) {
        return g.getDailyCredits() != null || g.getMonthlyCredits() != null || g.getYearlyCredits() != null
                || credits.existsByGrantId(g.getId());
    }

    /**
     * 周期积分惰性发放：当期首笔请求时补发（唯一约束防重，并发双发由 ON CONFLICT 兜底）。
     * 每日积分当天有效次日重发；每月/每年积分当期有效。发放失败不影响本次请求
     * （下一笔请求会重试发放）。
     */
    private void issuePeriodCredits(ProviderGrantEntity g) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now();
        if (g.getDailyCredits() != null && g.getDailyCredits().compareTo(BigDecimal.ZERO) > 0) {
            credits.issuePeriodCredit(g.getId(), "daily", today.toString(), g.getDailyCredits(),
                    today.plusDays(1).atStartOfDay(zone).toInstant());
        }
        if (g.getMonthlyCredits() != null && g.getMonthlyCredits().compareTo(BigDecimal.ZERO) > 0) {
            YearMonth ym = YearMonth.from(today);
            credits.issuePeriodCredit(g.getId(), "monthly", ym.toString(), g.getMonthlyCredits(),
                    ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
        }
        if (g.getYearlyCredits() != null && g.getYearlyCredits().compareTo(BigDecimal.ZERO) > 0) {
            int year = today.getYear();
            credits.issuePeriodCredit(g.getId(), "yearly", String.valueOf(year), g.getYearlyCredits(),
                    LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant());
        }
    }

    /**
     * FIFO 按量扣减 cost 积分：按过期时间升序逐行原子扣（consumed + amount ≤ credits 守卫
     * 防并发超发），单行剩余不足则跨行分摊；行被并发扣完则换下一行。
     * 余额不足时已扣部分保留（与「失败请求也消耗」口径一致），返回 false 由调用方拒绝请求。
     */
    private boolean consumeCredits(Long grantId, BigDecimal cost) {
        BigDecimal remaining = cost;
        for (ProviderCreditEntity c : credits.findConsumable(grantId, Instant.now())) {
            while (remaining.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal avail = c.getCredits().subtract(c.getConsumed());
                if (avail.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                BigDecimal take = avail.min(remaining);
                if (credits.tryConsumeAmount(c.getId(), take) == 1) {
                    remaining = remaining.subtract(take);
                } else {
                    break;
                }
            }
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                return true;
            }
        }
        return false;
    }

    /** 模型积分比例：目录登记值；未登记模型默认 1（含空模型名兜底）。 */
    private BigDecimal modelCost(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return BigDecimal.ONE;
        }
        return modelCatalog.findByModelName(modelName)
                .map(ModelCatalogEntity::getCreditCost)
                .orElse(BigDecimal.ONE);
    }

    /** 消耗记录落库（使用记录页面数据源）；失败不影响请求主流程。 */
    private void recordConsumption(ProviderGrantEntity g, Long providerId, String modelName, BigDecimal cost) {
        try {
            CreditConsumptionEntity row = new CreditConsumptionEntity();
            row.setGrantId(g.getId());
            row.setProviderId(providerId);
            row.setModelName(modelName == null ? "" : modelName);
            row.setCost(cost);
            consumptions.save(row);
        } catch (RuntimeException ignored) {
            // 使用记录为辅助数据，落库失败不阻断请求
        }
    }

    /** 积分池可用余额：Σ 未过期行的（面额 − 已消耗）；过期行不计入（惰性过期）。 */
    private BigDecimal remainingOf(Long grantId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ProviderCreditEntity c : credits.findByGrantIdAndExpiresAtAfter(grantId, Instant.now())) {
            BigDecimal left = c.getCredits().subtract(c.getConsumed());
            if (left.compareTo(BigDecimal.ZERO) > 0) {
                sum = sum.add(left);
            }
        }
        return sum;
    }

    /**
     * 积分构成：按周期类型（daily/monthly/yearly/temp）统计未过期剩余。
     * 缺失的周期类型补 0，保证四键齐全。
     */
    private Map<String, BigDecimal> compositionOf(Long grantId) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (String type : List.of("daily", "monthly", "yearly", "temp")) {
            m.put(type, BigDecimal.ZERO);
        }
        for (Object[] row : credits.sumRemainingByPeriodType(grantId, Instant.now())) {
            BigDecimal v = (BigDecimal) row[1];
            m.put((String) row[0], v == null ? BigDecimal.ZERO : v);
        }
        return m;
    }

    /** 积分值统一 1 位小数、多余位数舍弃不进位（DOWN）。 */
    private BigDecimal truncate1(BigDecimal v) {
        return v.setScale(1, java.math.RoundingMode.DOWN);
    }

    private ProviderCreditDto toCreditDto(ProviderCreditEntity c) {
        return new ProviderCreditDto(c.getId(), c.getPeriodType(), c.getPeriodKey(),
                c.getCredits(), c.getConsumed(),
                truncate1(c.getCredits().subtract(c.getConsumed()).max(BigDecimal.ZERO)),
                c.getExpiresAt(), c.getCreatedAt());
    }

    private ProviderGrantDto toDto(ProviderGrantEntity g, UserEntity user, LocalDate today) {
        Integer usedToday = g.getDailyLimit() == null ? null
                : usages.findByGrantIdAndUsageDate(g.getId(), today)
                        .map(ProviderGrantUsageEntity::getRequestCount).orElse(0);
        BigDecimal remaining = creditsEnabled(g) ? remainingOf(g.getId()) : null;
        return new ProviderGrantDto(g.getId(), g.getProviderId(), g.getUserId(),
                user == null ? null : user.getUsername(), g.getDailyLimit(), g.getExpiresAt(), usedToday,
                g.getDailyCredits(), g.getMonthlyCredits(), g.getYearlyCredits(), remaining);
    }
}
