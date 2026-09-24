package com.xinl.easyclaw.hub.service.blackboard;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardAppendRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardArchiveRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardBookInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardEntryInfo;
import com.xinl.easyclaw.hub.entity.BlackboardEntryEntity;
import com.xinl.easyclaw.hub.entity.ProjectEntity;
import com.xinl.easyclaw.hub.repository.BlackboardEntryRepository;
import com.xinl.easyclaw.hub.repository.ProjectRepository;
import com.xinl.easyclaw.hub.repository.UserRepository;
import com.xinl.easyclaw.hub.security.AppKeyContext;
import com.xinl.easyclaw.hub.service.AuditService;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * spoke 工作区黑板云同步服务（V24 起统一落平台黑板 blackboard_entries）：
 * spoke AI Agent 与 hub 平台用户共享同一块板（按 project 归属、跨 source 读写），为团队协作做准备。
 * 不再有独立 workspace_blackboard 表。
 *
 * <p>语义映射：append → blackboard_entries 追加一行（author_user_id=0 占位、source=workspace、
 * book_key=请求本名、status=active）；archive → 该 (projectId, key) 本内 source=workspace 的活跃行
 * 全部转 status=archived（平台条目不受影响）；books/entries → 按项目读（跨 source，人类记录 Agent 可见），
 * 归档本以虚拟键 {@code <key>.archived-<latestUpdatedMillis>} 表达（兼容 spoke 端归档键判定）。
 * seq 不再落库（追加型 id 即时间序），读端点按 id 升序动态编号，兼容 spoke 端排序语义。
 */
@Service
public class WorkspaceBlackboardService {

    private static final String ARCHIVED_MARK = ".archived-";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_ARCHIVED = "archived";
    private static final String SOURCE_WORKSPACE = "workspace";
    /** Agent 写入的 user 占位（无 hub 用户；与迁移 V24 的存量行口径一致）。 */
    private static final long AGENT_USER_ID = 0L;
    /** spoke 读端点对 Agent 条目返回的作者名（blackboard_entries 不存 spoke 侧 author 字符串）。 */
    private static final String AGENT_AUTHOR = "agent";

    private final BlackboardEntryRepository entries;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final AuditService auditService;

    public WorkspaceBlackboardService(BlackboardEntryRepository entries, ProjectRepository projects,
                                      UserRepository users, AuditService auditService) {
        this.entries = entries;
        this.projects = projects;
        this.users = users;
        this.auditService = auditService;
    }

    /** 追加一条：归档键拒绝（400）；落 blackboard_entries（source=workspace，author=0 占位）。 */
    @Transactional
    public SpokeBlackboardEntryInfo append(AppKeyContext ctx, SpokeBlackboardAppendRequest req) {
        if (req.key().contains(ARCHIVED_MARK)) {
            throw ApiException.validation("归档本不可追加");
        }
        ProjectEntity p = loadProjectOfOrg(req.projectId(), ctx.orgId());
        BlackboardEntryEntity e = new BlackboardEntryEntity();
        e.setProjectId(p.getId());
        e.setContent(req.content() == null ? "" : req.content());
        e.setAuthorUserId(AGENT_USER_ID);
        e.setStatus(STATUS_ACTIVE);
        e.setSource(SOURCE_WORKSPACE);
        e.setSourceWorkspaceId(req.workspaceId());
        e.setEntryType(req.type() == null || req.type().isBlank() ? "note" : req.type());
        e.setBookKey(req.key());
        entries.save(e);
        auditService.record(AuditModule.BLACKBOARD, "spoke_append_blackboard_entry", null, ctx.orgId(),
                "blackboard_entry", String.valueOf(e.getId()),
                "workspaceId=" + req.workspaceId() + ",bookKey=" + req.key() + ",projectId=" + p.getId(),
                AuditModule.SUCCESS);
        return toInfo(e, e.getId(), AGENT_AUTHOR);
    }

    /** 归档整本：该 (projectId, key) 本内 source=workspace 的活跃行全部转 archived；本不存在 → 404。 */
    @Transactional
    public void archive(AppKeyContext ctx, SpokeBlackboardArchiveRequest req) {
        ProjectEntity p = loadProjectOfOrg(req.projectId(), ctx.orgId());
        List<BlackboardEntryEntity> rows = entries.findByProjectIdAndSourceAndBookKeyAndStatus(
                p.getId(), SOURCE_WORKSPACE, req.key(), STATUS_ACTIVE);
        if (rows.isEmpty()) {
            throw ApiException.notFound("黑板本不存在");
        }
        for (BlackboardEntryEntity row : rows) {
            row.setStatus(STATUS_ARCHIVED);
        }
        entries.saveAll(rows);
        auditService.record(AuditModule.BLACKBOARD, "spoke_archive_blackboard_book", null, ctx.orgId(),
                "blackboard_entry", null,
                "workspaceId=" + req.workspaceId() + ",bookKey=" + req.key()
                        + ",projectId=" + p.getId() + ",entries=" + rows.size(),
                AuditModule.SUCCESS);
    }

    /**
     * 本清单（按 bookKey 升序）：活跃本 = 项目全部活跃条目（跨 source）按 book_key 分组；
     * 归档本 = 项目全部归档条目按 book_key 分组，虚拟键 {@code <key>.archived-<latestUpdatedMillis>}，
     * archivedAt = 组内最大 updated_at（兼容 spoke 端「归档键含 .archived-」判定与回查逻辑）。
     */
    public List<SpokeBlackboardBookInfo> books(AppKeyContext ctx, Long projectId) {
        ProjectEntity p = loadProjectOfOrg(projectId, ctx.orgId());
        Map<String, List<BlackboardEntryEntity>> activeByBook = groupByKey(
                entries.findByProjectIdAndStatusOrderByIdAsc(p.getId(), STATUS_ACTIVE));
        Map<String, List<BlackboardEntryEntity>> archivedByBook = groupByKey(
                entries.findByProjectIdAndStatusOrderByIdAsc(p.getId(), STATUS_ARCHIVED));
        List<SpokeBlackboardBookInfo> result = new ArrayList<>();
        activeByBook.forEach((key, rows) -> result.add(
                new SpokeBlackboardBookInfo(key, (long) rows.size(), latestMillis(rows), false, null)));
        archivedByBook.forEach((key, rows) -> {
            Long latest = latestMillis(rows);
            result.add(new SpokeBlackboardBookInfo(key + ARCHIVED_MARK + (latest == null ? 0L : latest),
                    (long) rows.size(), latest, true, latest));
        });
        return result.stream().sorted(Comparator.comparing(SpokeBlackboardBookInfo::key)).toList();
    }

    /**
     * 某本条目（按 id 升序，seq 动态编号）：归档键（含 .archived-）→ 查主键名的 archived 行；
     * 活跃键 → 查该本 active 行。均跨 source（人类记录 Agent 可读，协作核心）。
     */
    public List<SpokeBlackboardEntryInfo> entries(AppKeyContext ctx, Long projectId, String bookKey) {
        ProjectEntity p = loadProjectOfOrg(projectId, ctx.orgId());
        boolean archived = bookKey.contains(ARCHIVED_MARK);
        String realKey = archived ? bookKey.substring(0, bookKey.lastIndexOf(ARCHIVED_MARK)) : bookKey;
        String status = archived ? STATUS_ARCHIVED : STATUS_ACTIVE;
        List<BlackboardEntryEntity> rows =
                entries.findByProjectIdAndBookKeyAndStatusOrderByIdAsc(p.getId(), realKey, status);
        Map<Long, String> names = resolveUsernames(rows);
        long seq = 0;
        List<SpokeBlackboardEntryInfo> result = new ArrayList<>();
        for (BlackboardEntryEntity e : rows) {
            String author = SOURCE_WORKSPACE.equals(e.getSource())
                    ? AGENT_AUTHOR
                    : names.getOrDefault(e.getAuthorUserId(), "#" + e.getAuthorUserId());
            result.add(toInfo(e, ++seq, author));
        }
        return result;
    }

    /** 按 book_key 分组（null book_key 视为 main，防御存量异常行）。 */
    private Map<String, List<BlackboardEntryEntity>> groupByKey(List<BlackboardEntryEntity> rows) {
        return rows.stream().collect(Collectors.groupingBy(
                e -> e.getBookKey() == null ? "main" : e.getBookKey()));
    }

    /** 组内最大 updated_at epoch 毫秒（全空返回 null）。 */
    private static Long latestMillis(List<BlackboardEntryEntity> rows) {
        return rows.stream()
                .map(BlackboardEntryEntity::getUpdatedAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .map(Instant::toEpochMilli)
                .orElse(null);
    }

    /** 平台条目的作者用户名（Agent 条目 author=0 不查，读端点固定返回 "agent"）。 */
    private Map<Long, String> resolveUsernames(List<BlackboardEntryEntity> rows) {
        Set<Long> ids = rows.stream().map(BlackboardEntryEntity::getAuthorUserId)
                .filter(id -> id != null && id != AGENT_USER_ID)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(ids).stream()
                .collect(Collectors.toMap(u -> u.getId(), u -> u.getUsername()));
    }

    /** 项目须存在且属于 appkey 组织（他组织项目 404，不暴露存在性）。 */
    private ProjectEntity loadProjectOfOrg(Long projectId, Long orgId) {
        if (projectId == null) {
            throw ApiException.validation("projectId 必填：工作区须绑定 hub 项目后才能同步黑板");
        }
        ProjectEntity p = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("项目不存在"));
        if (!orgId.equals(p.getOrgId())) {
            throw ApiException.notFound("项目不存在");
        }
        return p;
    }

    /** ts = created_at（ISO-8601，服务端生成语义保留）。 */
    private static SpokeBlackboardEntryInfo toInfo(BlackboardEntryEntity e, long seq, String author) {
        String ts = e.getCreatedAt() == null
                ? DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                : DateTimeFormatter.ISO_INSTANT.format(e.getCreatedAt());
        return new SpokeBlackboardEntryInfo(seq, ts, author, e.getEntryType(), e.getContent());
    }
}
