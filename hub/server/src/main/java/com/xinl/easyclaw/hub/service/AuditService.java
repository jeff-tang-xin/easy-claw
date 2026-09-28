package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.contract.audit.AuditLogDto;
import com.xinl.easyclaw.hub.contract.audit.AuditLogPageResponse;
import com.xinl.easyclaw.hub.security.AuthContext;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.entity.AuditLogEntity;
import com.xinl.easyclaw.hub.repository.AuditLogRepository;

/**
 * 审计记录服务：各业务服务注入调用 {@link #record} 落一条审计。
 * actor 用户名 / IP / UA 由本类从当前请求上下文尽力回填（非 Web 上下文如测试/后台则留空），
 * 业务方只需传入「模块/动作/操作者 id/目标/结果/明细」，保持调用点干净。
 */
@Service
public class AuditService {

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    /**
     * 记录一条审计。
     *
     * @param module      模块（{@link AuditModule}#AUTH/ORG/PROJECT/USER）
     * @param action      动作（小写蛇形，如 create_org）
     * @param actorUserId 操作者用户 id（系统/匿名/登录失败传 null）
     * @param orgId       相关组织 id（平台级事件传 null）
     * @param targetType  目标类型（user|organization|project...，可空）
     * @param targetId    目标 id（字符串，可空）
     * @param detail      附加上下文（建议 JSON 字符串，可空）
     * @param result      结果（{@link AuditModule}#SUCCESS/FAILURE，空则按 success）
     */
    public void record(String module, String action, Long actorUserId, Long orgId,
                       String targetType, String targetId, String detail, String result) {
        AuditLogEntity e = new AuditLogEntity();
        e.setModule(module);
        e.setAction(action);
        e.setActorUserId(actorUserId);
        e.setActorUsername(resolveUsername(actorUserId));
        e.setOrgId(orgId);
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        e.setResult(result == null || result.isBlank() ? AuditModule.SUCCESS : result);
        e.setDetail(detail);
        fillRequestContext(e);
        repo.save(e);
    }

    /** 组织维度分页查询（最新在前）；module 为空则不筛。size 上限 100 防拖库。 */
    public AuditLogPageResponse query(Long orgId, String module, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(p, s);
        boolean byModule = module != null && !module.isBlank();
        List<AuditLogEntity> rows = byModule
                ? repo.findByOrgIdAndModuleOrderByCreatedAtDesc(orgId, module, pageable)
                : repo.findByOrgIdOrderByCreatedAtDesc(orgId, pageable);
        long total = byModule ? repo.countByOrgIdAndModule(orgId, module) : repo.countByOrgId(orgId);
        return new AuditLogPageResponse(rows.stream().map(AuditService::toDto).toList(), total, p, s);
    }

    private static AuditLogDto toDto(AuditLogEntity e) {
        return new AuditLogDto(e.getId(), e.getModule(), e.getAction(), e.getActorUserId(), e.getActorUsername(),
                e.getOrgId(), e.getTargetType(), e.getTargetId(), e.getResult(), e.getDetail(), e.getIp(),
                e.getUserAgent(),
                e.getCreatedAt() == null ? null : LocalDateTime.ofInstant(e.getCreatedAt(), ZoneId.systemDefault()));
    }

    /** 操作者是当前请求用户时回填其用户名；否则留空（由 detail 体现尝试对象）。 */
    private String resolveUsername(Long actorUserId) {
        AuthContext ctx = CurrentUserHolder.get();
        if (ctx != null && ctx.username() != null && (actorUserId == null || actorUserId.equals(ctx.userId()))) {
            return ctx.username();
        }
        return null;
    }

    private void fillRequestContext(AuditLogEntity e) {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                HttpServletRequest req = attrs.getRequest();
                e.setIp(clientIp(req));
                String ua = req.getHeader("User-Agent");
                e.setUserAgent(ua != null && ua.length() > 255 ? ua.substring(0, 255) : ua);
            }
        } catch (Exception ignore) {
            // 非 Web 上下文（测试/后台任务）：留空即可。
        }
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
