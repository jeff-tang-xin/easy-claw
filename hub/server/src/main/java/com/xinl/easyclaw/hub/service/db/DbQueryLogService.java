package com.xinl.easyclaw.hub.service.db;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.contract.db.DbQueryLogDto;
import com.xinl.easyclaw.hub.contract.spoke.SpokeDbQueryLogReport;
import com.xinl.easyclaw.hub.entity.DbConnectionEntity;
import com.xinl.easyclaw.hub.entity.DbQueryLogEntity;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.DbConnectionRepository;
import com.xinl.easyclaw.hub.repository.DbQueryLogRepository;
import com.xinl.easyclaw.hub.repository.UserRepository;
import com.xinl.easyclaw.hub.security.AppKeyContext;
import com.xinl.easyclaw.hub.service.PlatformAdminGuard;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据库查询审计服务（db_query_logs，V30）：spoke 执行查询后批量上报落库，
 * platformAdmin 按连接分页审计查询。追加型日志，只写不改不删。
 * 口径同 {@link com.xinl.easyclaw.hub.service.ops.OpsCommandLogService}（V25）。
 */
@Service
public class DbQueryLogService {

    /** 单批上报上限（与 SpokeDbQueryLogReport 的 @Size(max=200) 同口径，服务端再裁决一次）。 */
    private static final int MAX_BATCH = 200;

    /** 管理端分页 size 上限。 */
    private static final int MAX_PAGE_SIZE = 100;

    private static final Set<String> ALLOWED_SOURCES = Set.of("ai", "user");

    private final DbQueryLogRepository repo;
    private final DbConnectionRepository connections;
    private final UserRepository users;
    private final PlatformAdminGuard guard;

    public DbQueryLogService(DbQueryLogRepository repo, DbConnectionRepository connections,
                             UserRepository users, PlatformAdminGuard guard) {
        this.repo = repo;
        this.connections = connections;
        this.users = users;
        this.guard = guard;
    }

    /**
     * spoke 批量上报查询记录：org/operator 取 appkey 上下文（operator = appkey 创建人用户名，
     * displayName 优先、兜底 username，用户已删除则兜底 userId 字符串），
     * serverKey/serverName/dbType/host/databaseName 为 spoke 侧快照（不回查目录）。
     * source 仅允许 ai | user；executedAt 为 ISO-8601，解析失败回退当前时间。
     */
    @Transactional
    public void report(AppKeyContext ctx, List<SpokeDbQueryLogReport.Item> logs) {
        if (logs == null || logs.isEmpty()) {
            throw ApiException.validation("logs 不能为空");
        }
        if (logs.size() > MAX_BATCH) {
            throw ApiException.validation("单批最多上报 " + MAX_BATCH + " 条");
        }
        Long orgId = ctx.orgId() == null ? 0L : ctx.orgId();
        String operator = resolveOperator(ctx.userId());
        Instant fallback = Instant.now();
        List<DbQueryLogEntity> rows = new ArrayList<>(logs.size());
        for (SpokeDbQueryLogReport.Item item : logs) {
            String source = item.source() == null ? "" : item.source().trim();
            if (!ALLOWED_SOURCES.contains(source)) {
                throw ApiException.validation("source 仅允许 ai | user");
            }
            if (item.serverKey() == null || item.serverKey().isBlank()) {
                throw ApiException.validation("serverKey 不能为空");
            }
            DbQueryLogEntity e = new DbQueryLogEntity();
            e.setOrgId(orgId);
            e.setServerKey(item.serverKey().trim());
            e.setServerName(item.serverName() == null ? "" : item.serverName().trim());
            e.setDbType(item.dbType() == null ? "" : item.dbType().trim());
            e.setHost(item.host() == null ? "" : item.host().trim());
            e.setDatabaseName(item.databaseName() == null ? "" : item.databaseName().trim());
            e.setSqlText(item.sqlText() == null ? "" : item.sqlText());
            e.setSource(source);
            e.setOperator(operator);
            e.setExecutedAt(parseExecutedAt(item.executedAt(), fallback));
            rows.add(e);
        }
        repo.saveAll(rows);
    }

    /**
     * 按连接分页查询查询记录（platformAdmin）：按该连接的 org_id+server_key 过滤，
     * 执行时间倒序（同秒内按 id 倒序保证稳定）。
     */
    @Transactional(readOnly = true)
    public DbQueryLogDto.PageResponse listByConnection(Long requesterId, Long connectionId,
                                                       int page, int size) {
        guard.require(requesterId);
        DbConnectionEntity connection = connections.findById(connectionId)
                .orElseThrow(() -> ApiException.notFound("数据库连接不存在"));
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(p, s);
        Page<DbQueryLogEntity> result = repo.findByOrgIdAndServerKeyOrderByExecutedAtDescIdDesc(
                connection.getOrgId(), connection.getServerKey(), pageable);
        List<DbQueryLogDto> content = result.getContent().stream().map(DbQueryLogService::toDto).toList();
        return new DbQueryLogDto.PageResponse(content, result.getTotalElements(), p, s);
    }

    private String resolveOperator(Long userId) {
        if (userId == null) {
            return "";
        }
        return users.findById(userId)
                .map(u -> u.getDisplayName() == null || u.getDisplayName().isBlank()
                        ? u.getUsername() : u.getDisplayName())
                .orElse(String.valueOf(userId));
    }

    private static Instant parseExecutedAt(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private static DbQueryLogDto toDto(DbQueryLogEntity e) {
        return new DbQueryLogDto(e.getId(), e.getServerKey(), e.getServerName(), e.getDbType(),
                e.getHost(), e.getDatabaseName(), e.getSqlText(), e.getSource(), e.getOperator(),
                e.getExecutedAt());
    }
}
