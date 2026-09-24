package com.xinl.easyclaw.hub.service.ops;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.contract.ops.OpsCommandLogDto;
import com.xinl.easyclaw.hub.contract.ops.OpsCommandLogPageResponse;
import com.xinl.easyclaw.hub.contract.spoke.SpokeOpsCommandLogReport;
import com.xinl.easyclaw.hub.entity.OpsCommandLogEntity;
import com.xinl.easyclaw.hub.entity.OpsServerEntity;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.OpsCommandLogRepository;
import com.xinl.easyclaw.hub.repository.OpsServerRepository;
import com.xinl.easyclaw.hub.repository.UserRepository;
import com.xinl.easyclaw.hub.security.AppKeyContext;
import com.xinl.easyclaw.hub.service.PlatformAdminGuard;
import java.time.Instant;
import java.time.format.DateTimeParseException;import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运维命令记录服务（ops_command_logs，V25）：spoke 执行命令后批量上报落库，
 * platformAdmin 按服务器分页审计查询。追加型日志，只写不改不删。
 */
@Service
public class OpsCommandLogService {

    /** 单批上报上限（与 SpokeOpsCommandLogReport 的 @Size(max=200) 同口径，服务端再裁决一次）。 */
    private static final int MAX_BATCH = 200;

    /** 管理端分页 size 上限（口径同 GatewayLogService）。 */
    private static final int MAX_PAGE_SIZE = 100;

    private static final Set<String> ALLOWED_SOURCES = Set.of("ai", "user");

    private final OpsCommandLogRepository repo;
    private final OpsServerRepository servers;
    private final UserRepository users;
    private final PlatformAdminGuard guard;

    public OpsCommandLogService(OpsCommandLogRepository repo, OpsServerRepository servers,
                                UserRepository users, PlatformAdminGuard guard) {
        this.repo = repo;
        this.servers = servers;
        this.users = users;
        this.guard = guard;
    }

    /**
     * spoke 批量上报命令记录：org/operator 取 appkey 上下文（operator = appkey 创建人用户名，
     * displayName 优先、兜底 username，用户已删除则兜底 userId 字符串），
     * serverKey/serverName/host 为 spoke 侧快照（不回查目录，服务器改名不影响历史行）。
     * source 仅允许 ai | user；executedAt 为 ISO-8601，解析失败回退当前时间（不因脏时间戳拒绝整批）。
     */
    @Transactional
    public void report(AppKeyContext ctx, List<SpokeOpsCommandLogReport.Item> logs) {
        if (logs == null || logs.isEmpty()) {
            throw ApiException.validation("logs 不能为空");
        }
        if (logs.size() > MAX_BATCH) {
            throw ApiException.validation("单批最多上报 " + MAX_BATCH + " 条");
        }
        Long orgId = ctx.orgId() == null ? 0L : ctx.orgId();
        String operator = resolveOperator(ctx.userId());
        Instant fallback = Instant.now();
        List<OpsCommandLogEntity> rows = new ArrayList<>(logs.size());
        for (SpokeOpsCommandLogReport.Item item : logs) {
            String source = item.source() == null ? "" : item.source().trim();
            if (!ALLOWED_SOURCES.contains(source)) {
                throw ApiException.validation("source 仅允许 ai | user");
            }
            if (item.serverKey() == null || item.serverKey().isBlank()) {
                throw ApiException.validation("serverKey 不能为空");
            }
            OpsCommandLogEntity e = new OpsCommandLogEntity();
            e.setOrgId(orgId);
            e.setServerKey(item.serverKey().trim());
            e.setServerName(item.serverName() == null ? "" : item.serverName().trim());
            e.setHost(item.host() == null ? "" : item.host().trim());
            e.setCommand(item.command() == null ? "" : item.command());
            e.setSource(source);
            e.setOperator(operator);
            e.setExecutedAt(parseExecutedAt(item.executedAt(), fallback));
            rows.add(e);
        }
        repo.saveAll(rows);
    }

    /**
     * 按服务器分页查询命令记录（platformAdmin）：按该 server 的 org_id+server_key 过滤，
     * 执行时间倒序（同秒内按 id 倒序保证稳定）。
     */
    @Transactional(readOnly = true)
    public OpsCommandLogPageResponse listByServer(Long requesterId, Long serverId, int page, int size) {
        guard.require(requesterId);
        OpsServerEntity server = servers.findById(serverId)
                .orElseThrow(() -> ApiException.notFound("运维服务器不存在"));
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<OpsCommandLogEntity> result = repo.findByOrgIdAndServerKeyOrderByExecutedAtDescIdDesc(
                server.getOrgId(), server.getServerKey(), PageRequest.of(p, s));
        return new OpsCommandLogPageResponse(
                result.getContent().stream().map(OpsCommandLogService::toDto).toList(),
                result.getTotalElements(), p, s);
    }

    /** appkey 创建人 userId → 展示名：displayName 优先、兜底 username；用户不存在兜底 userId 字符串；null 兜底空串。 */
    private String resolveOperator(Long userId) {
        if (userId == null) {
            return "";
        }
        return users.findById(userId)
                .map(u -> {
                    String name = u.getDisplayName();
                    return name == null || name.isBlank() ? u.getUsername() : name.trim();
                })
                .orElseGet(() -> String.valueOf(userId));
    }

    /** ISO-8601 解析；空/解析失败回退 fallback（批量内共享同一回退时刻）。 */
    private static Instant parseExecutedAt(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            return fallback;
        }
    }

    private static OpsCommandLogDto toDto(OpsCommandLogEntity e) {
        return new OpsCommandLogDto(e.getId(), e.getServerKey(), e.getServerName(), e.getHost(),
                e.getCommand(), e.getSource(), e.getOperator(), e.getExecutedAt(), e.getCreatedAt());
    }
}
