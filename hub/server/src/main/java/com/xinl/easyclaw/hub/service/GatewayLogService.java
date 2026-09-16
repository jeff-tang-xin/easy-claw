package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.contract.gateway.GatewayLogDetailDto;
import com.xinl.easyclaw.hub.contract.gateway.GatewayLogListItemDto;
import com.xinl.easyclaw.hub.contract.gateway.GatewayLogPageResponse;
import com.xinl.easyclaw.hub.contract.gateway.GatewayUsageDto;
import com.xinl.easyclaw.hub.entity.GatewayLogEntity;
import com.xinl.easyclaw.hub.repository.GatewayLogRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * 网关详单/用量查询（设计 §4.3）：组织维度，仅 owner/admin 可见（权限判定在 controller，口径与审计一致）。
 * 列表不返回正文；详情带完整（可能已截断）请求/响应体。
 */
@Service
public class GatewayLogService {

    private final GatewayLogRepository logs;

    public GatewayLogService(GatewayLogRepository logs) {
        this.logs = logs;
    }

    /** 组织维度分页（最新在前），可选 status/model/keyPrefix 过滤；size 上限 100。 */
    public GatewayLogPageResponse query(Long orgId, String status, String model, String keyPrefix,
                                        int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<GatewayLogEntity> result = logs.findAll(buildSpec(orgId, status, model, keyPrefix), pageable);
        return new GatewayLogPageResponse(
                result.getContent().stream().map(GatewayLogService::toListItem).toList(),
                result.getTotalElements(), p, s);
    }

    /** 详单详情（组织内按 id 精确取；跨组织 id 返回 404 不泄露存在性）。 */
    public GatewayLogDetailDto detail(Long orgId, Long id) {
        GatewayLogEntity e = logs.findById(id)
                .filter(g -> g.getOrgId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("详单不存在"));
        return toDetail(e);
    }

    /** 用量聚合：近 days 天（1~90，默认 30），总量 + 按模型分布。 */
    public GatewayUsageDto usage(Long orgId, Integer days) {
        int d = days == null ? 30 : Math.min(Math.max(days, 1), 90);
        Instant since = Instant.now().minus(d, ChronoUnit.DAYS);
        Object[] total = logs.summarizeUsage(orgId, since).get(0);
        List<GatewayUsageDto.ModelUsage> byModel = logs.summarizeUsageByModel(orgId, since).stream()
                .map(r -> new GatewayUsageDto.ModelUsage((String) r[0], toLong(r[1]), toLong(r[2]), toLong(r[3])))
                .toList();
        return new GatewayUsageDto(d, toLong(total[0]), toLong(total[1]), toLong(total[2]), toLong(total[3]),
                byModel);
    }

    private static Specification<GatewayLogEntity> buildSpec(Long orgId, String status, String model,
                                                             String keyPrefix) {
        return (root, q, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("orgId"), orgId));
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (model != null && !model.isBlank()) {
                predicates.add(cb.equal(root.get("model"), model));
            }
            if (keyPrefix != null && !keyPrefix.isBlank()) {
                predicates.add(cb.equal(root.get("keyPrefix"), keyPrefix));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private static GatewayLogListItemDto toListItem(GatewayLogEntity e) {
        return new GatewayLogListItemDto(e.getId(), toLocalDateTime(e.getCreatedAt()), e.getAppKeyId(),
                e.getKeyPrefix(), e.getUserId(), e.getProviderId(), e.getProviderSlug(), e.getApiType(),
                e.getModel(), e.isStream(), e.getStatus(), e.getHttpStatus(), e.getLatencyMs(),
                e.getPromptTokens(), e.getCompletionTokens(), e.getErrorMessage());
    }

    private static GatewayLogDetailDto toDetail(GatewayLogEntity e) {
        return new GatewayLogDetailDto(e.getId(), toLocalDateTime(e.getCreatedAt()), e.getAppKeyId(),
                e.getKeyPrefix(), e.getUserId(), e.getProviderId(), e.getProviderSlug(), e.getApiType(),
                e.getModel(), e.isStream(), e.getStatus(), e.getHttpStatus(), e.getLatencyMs(),
                e.getPromptTokens(), e.getCompletionTokens(), e.getErrorMessage(),
                e.getRequestBody(), e.getResponseBody(), e.getClientIp());
    }
}
