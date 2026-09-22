package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.contract.knowledge.CreateKnowledgeItemRequest;
import com.xinl.easyclaw.hub.contract.knowledge.KnowledgeHistoryItemDto;
import com.xinl.easyclaw.hub.contract.knowledge.KnowledgeHistoryVersionDto;
import com.xinl.easyclaw.hub.contract.knowledge.KnowledgeItemDto;
import com.xinl.easyclaw.hub.contract.knowledge.KnowledgeItemListItemDto;
import com.xinl.easyclaw.hub.contract.knowledge.UpdateKnowledgeItemRequest;
import com.xinl.easyclaw.hub.entity.KnowledgeItemEntity;
import com.xinl.easyclaw.hub.entity.KnowledgeItemEventEntity;
import com.xinl.easyclaw.hub.entity.ProjectEntity;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.KnowledgeItemEventRepository;
import com.xinl.easyclaw.hub.repository.KnowledgeItemRepository;
import com.xinl.easyclaw.hub.repository.ProjectRepository;
import com.xinl.easyclaw.hub.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目知识库条目服务（A3-S1）：CRUD + 乐观锁 + 软删 + 追加型版本历史，无向量/检索（留待 S2/S3）。
 *
 * <p>权限与项目可见性对齐（同 {@link ProjectService} 口径）：读取按 project 的 private/team/public + 组织角色；
 * 写入需可看到项目且非 guest；删除仅限组织 owner/admin 或条目创建者。
 *
 * <p>并发：更新走 {@code UPDATE ... WHERE id=? AND version=? AND status='active'} 乐观锁，0 行即
 * 版本冲突（抛 409 {@code VERSION_CONFLICT}，details 携带最新快照）或条目已删（404）。
 * topic 项目内唯一（仅 active，DB 部分唯一索引兜底），撞名抛 409 {@code TOPIC_EXISTS}。
 * 每次创建/更新在 knowledge_item_events 追加一行不可变快照。无外键（V4 决策），软删保留历史。
 */
@Service
public class KnowledgeService {

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_DELETED = "deleted";

    private final KnowledgeItemRepository items;
    private final KnowledgeItemEventRepository events;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final OrgService orgService;
    private final AuditService auditService;
    private final EntityManager em;

    public KnowledgeService(KnowledgeItemRepository items, KnowledgeItemEventRepository events,
                            ProjectRepository projects, UserRepository users, OrgService orgService,
                            AuditService auditService, EntityManager em) {
        this.items = items;
        this.events = events;
        this.projects = projects;
        this.users = users;
        this.orgService = orgService;
        this.auditService = auditService;
        this.em = em;
    }

    // ---------- 查询 ----------

    public List<KnowledgeItemListItemDto> list(Long requesterId, Long projectId, String query) {
        ProjectEntity p = loadProject(projectId);
        requireCanRead(p, requesterId);
        List<KnowledgeItemEntity> rows;
        var terms = splitTerms(query);
        if (terms.isEmpty()) {
            rows = items.findByProjectIdAndStatusOrderByUpdatedAtDesc(projectId, STATUS_ACTIVE);
        } else {
            // 跨库（PG/SQLite）：LOWER + LIKE，多词 AND；通配符转义并显式 ESCAPE，避免用户输入 % _ 改变语义。
            rows = searchByKeywords(projectId, terms);
        }
        Map<Long, String> names = resolveUsernames(collectUserIds(rows));
        return rows.stream().map(d -> toListItem(d, names)).toList();
    }

    /** 按空白拆词并去空白项；null/空白返回空列表（= 不带检索）。 */
    private static List<String> splitTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(query.trim().split("\\s+"))
                .filter(s -> !s.isBlank()).toList();
    }

    /** 转义 LIKE 的三个特殊字符（\ % _），配合查询里的 ESCAPE '\\'。 */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 关键词检索（A3-S2）：在 topic/summary/content 拼接串上做大小写不敏感 LIKE，多个词 AND。
     * 用 JPA Criteria 按词数动态生成条件（JPQL 无集合参数的 LIKE ALL），CONCAT/LOWER/LIKE...ESCAPE
     * 在 PG 与 SQLite 上均可移植；按 updatedAt 倒序。pattern 已加 {@code %} 并转义通配符。
     */
    private List<KnowledgeItemEntity> searchByKeywords(Long projectId, List<String> terms) {
        var cb = em.getCriteriaBuilder();
        var cq = cb.createQuery(KnowledgeItemEntity.class);
        var k = cq.from(KnowledgeItemEntity.class);
        var haystack = cb.lower(cb.concat(cb.concat(cb.concat(k.get("topic"), " "),
                cb.concat(k.get("summary"), " ")), k.get("content")));
        var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
        predicates.add(cb.equal(k.get("projectId"), projectId));
        predicates.add(cb.equal(k.get("status"), STATUS_ACTIVE));
        for (String term : terms) {
            String pattern = "%" + escapeLike(term.toLowerCase()) + "%";
            predicates.add(cb.like(haystack, pattern, '\\'));
        }
        cq.select(k).where(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]))
                .orderBy(cb.desc(k.get("updatedAt")));
        return em.createQuery(cq).getResultList();
    }

    public KnowledgeItemDto get(Long requesterId, Long itemId) {
        KnowledgeItemEntity d = loadActiveItem(itemId);
        ProjectEntity p = loadProject(d.getProjectId());
        requireCanRead(p, requesterId);
        return toDto(d, resolveUsernames(idSet(d.getOwnerUserId(), d.getUpdatedBy())));
    }

    public List<KnowledgeHistoryItemDto> history(Long requesterId, Long itemId) {
        // 不依赖当前态表：条目软删后历史仍要可查；project id 从事件行冗余列取（首行足够，同条同项目）。
        List<KnowledgeItemEventEntity> rows = events.findByItemIdOrderByVersionDesc(itemId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("知识条目不存在");
        }
        ProjectEntity p = loadProject(rows.get(0).getProjectId());
        requireCanRead(p, requesterId);
        Map<Long, String> names = resolveUsernames(collectActorIds(rows));
        return rows.stream()
                .map(e -> new KnowledgeHistoryItemDto(e.getVersion(), e.getTopic(), e.getSummary(),
                        e.getActorUserId(), names.get(e.getActorUserId()), ldt(e.getCreatedAt())))
                .toList();
    }

    public KnowledgeHistoryVersionDto historyVersion(Long requesterId, Long itemId, Long version) {
        KnowledgeItemEventEntity e = events.findByItemIdAndVersion(itemId, version)
                .orElseThrow(() -> ApiException.notFound("历史版本不存在"));
        ProjectEntity p = loadProject(e.getProjectId());
        requireCanRead(p, requesterId);
        Map<Long, String> names = resolveUsernames(idSet(e.getActorUserId()));
        return new KnowledgeHistoryVersionDto(e.getVersion(), e.getTopic(), e.getSummary(), e.getContent(),
                e.getActorUserId(), names.get(e.getActorUserId()), ldt(e.getCreatedAt()));
    }

    // ---------- 写入 ----------

    @Transactional
    public KnowledgeItemDto create(Long requesterId, CreateKnowledgeItemRequest req) {
        ProjectEntity p = loadProject(req.projectId());
        requireCanWrite(p, requesterId);
        String topic = req.topic().trim();
        ensureTopicAvailable(p.getId(), topic, null);

        KnowledgeItemEntity d = new KnowledgeItemEntity();
        d.setProjectId(p.getId());
        d.setTopic(topic);
        d.setSummary(emptyIfNull(req.summary()));
        d.setContent(emptyIfNull(req.content()));
        d.setStatus(STATUS_ACTIVE);
        d.setOwnerUserId(requesterId);
        d.setUpdatedBy(requesterId);
        d.setVersion(1L);
        items.save(d);
        appendEvent(d, requesterId);
        auditService.record(AuditModule.KNOWLEDGE, "create_knowledge_item", requesterId, p.getOrgId(),
                "knowledge_item", String.valueOf(d.getId()), "topic=" + topic, AuditModule.SUCCESS);
        return toDto(d, resolveUsernames(idSet(d.getOwnerUserId(), d.getUpdatedBy())));
    }

    @Transactional
    public KnowledgeItemDto update(Long requesterId, Long itemId, UpdateKnowledgeItemRequest req) {
        KnowledgeItemEntity d = loadActiveItem(itemId);
        ProjectEntity p = loadProject(d.getProjectId());
        requireCanWrite(p, requesterId);

        String topic = req.topic().trim();
        String summary = emptyIfNull(req.summary());
        String content = emptyIfNull(req.content());
        ensureTopicAvailable(p.getId(), topic, itemId);

        // 条件更新：active 且 version 未被他人推进才成功；清空持久化上下文后重读拿 DB 真实状态。
        int affected = items.updateContentIfVersion(itemId, topic, summary, content, requesterId, req.expectedVersion());
        if (affected == 0) {
            items.flush();
            em.detach(d);
            KnowledgeItemEntity latest = items.findById(itemId).orElse(null);
            if (latest == null || STATUS_DELETED.equals(latest.getStatus())) {
                throw ApiException.notFound("知识条目不存在");
            }
            Map<Long, String> names = resolveUsernames(idSet(latest.getOwnerUserId(), latest.getUpdatedBy()));
            throw ApiException.versionConflict(
                    "知识条目已被他人更新，请合并后再保存（你的编辑区内容不会被覆盖）", toDto(latest, names));
        }
        items.flush();
        em.detach(d);
        KnowledgeItemEntity saved = loadActiveItem(itemId);
        appendEvent(saved, requesterId);
        auditService.record(AuditModule.KNOWLEDGE, "update_knowledge_item", requesterId, p.getOrgId(),
                "knowledge_item", String.valueOf(itemId), "version=" + saved.getVersion(), AuditModule.SUCCESS);
        return toDto(saved, resolveUsernames(idSet(saved.getOwnerUserId(), saved.getUpdatedBy())));
    }

    @Transactional
    public void delete(Long requesterId, Long itemId) {
        KnowledgeItemEntity d = loadActiveItem(itemId);
        ProjectEntity p = loadProject(d.getProjectId());
        requireCanManage(d, p, requesterId);
        // 软删：保留事件历史；部分唯一索引（WHERE status='active'）放行日后重建同名条目。
        d.setStatus(STATUS_DELETED);
        items.save(d);
        auditService.record(AuditModule.KNOWLEDGE, "delete_knowledge_item", requesterId, p.getOrgId(),
                "knowledge_item", String.valueOf(itemId), "topic=" + d.getTopic(), AuditModule.SUCCESS);
    }

    // ---------- 权限 ----------

    /** 读：与 {@link ProjectService#get} 同口径——public 组织外可读；team/private 需成员且能看到项目。 */
    private void requireCanRead(ProjectEntity p, Long requesterId) {
        if (!canSeeProject(p, requesterId)) {
            throw ApiException.forbidden("无权限查看该项目的知识库");
        }
    }

    /** 写：先须能看到项目（收紧 private 越权写），再要求非 guest 角色。 */
    private void requireCanWrite(ProjectEntity p, Long requesterId) {
        if (!canSeeProject(p, requesterId)) {
            throw ApiException.forbidden("无权限操作该项目的知识库");
        }
        String role = orgService.roleOf(p.getOrgId(), requesterId);
        if ("guest".equals(role)) {
            throw ApiException.forbidden("访客只读，不能编辑知识条目");
        }
    }

    /** 项目可见性，口径与 {@link ProjectService} 完全一致：public 全开；非 public 需成员；private 仅 owner/admin+创建者。 */
    private boolean canSeeProject(ProjectEntity p, Long requesterId) {
        String role = orgService.roleOf(p.getOrgId(), requesterId);
        boolean member = role != null;
        if ("public".equals(p.getVisibility())) {
            return true;
        }
        if (!member) {
            return false;
        }
        boolean privileged = "owner".equals(role) || "admin".equals(role);
        return privileged
                || "team".equals(p.getVisibility())
                || p.getOwnerUserId().equals(requesterId);
    }

    /** 管理（删除）：组织 owner/admin 或条目创建者。 */
    private void requireCanManage(KnowledgeItemEntity d, ProjectEntity p, Long requesterId) {
        String role = orgService.roleOf(p.getOrgId(), requesterId);
        boolean canManage = "owner".equals(role) || "admin".equals(role)
                || d.getOwnerUserId().equals(requesterId);
        if (!canManage) {
            throw ApiException.forbidden("无权限删除该知识条目");
        }
    }

    // ---------- 辅助 ----------

    private ProjectEntity loadProject(Long projectId) {
        return projects.findById(projectId).orElseThrow(() -> ApiException.notFound("项目不存在"));
    }

    /** 取未删除条目；软删/不存在统一按 404（不向枚举者暴露已删条目）。 */
    private KnowledgeItemEntity loadActiveItem(Long itemId) {
        KnowledgeItemEntity d = items.findById(itemId).orElseThrow(() -> ApiException.notFound("知识条目不存在"));
        if (STATUS_DELETED.equals(d.getStatus())) {
            throw ApiException.notFound("知识条目不存在");
        }
        return d;
    }

    /**
     * topic 项目内唯一（仅 active）：excludeId 为更新时的自身 id。
     * 应用层先查给用户明确 409；DB 部分唯一索引兜底最后并发（撞索引由全局异常处理转 500 的概率极低，
     * 且本方法 + 唯一索引双层已覆盖常规场景，与知识条目唯一键的处理口径一致）。
     */
    private void ensureTopicAvailable(Long projectId, String topic, Long excludeId) {
        items.findByProjectIdAndTopicAndStatus(projectId, topic, STATUS_ACTIVE)
                .ifPresent(existing -> {
                    if (!Objects.equals(existing.getId(), excludeId)) {
                        throw ApiException.topicExists("同项目下已存在同名知识条目：" + topic);
                    }
                });
    }

    private void appendEvent(KnowledgeItemEntity d, Long actorUserId) {
        KnowledgeItemEventEntity e = new KnowledgeItemEventEntity();
        e.setItemId(d.getId());
        e.setProjectId(d.getProjectId());
        e.setVersion(d.getVersion());
        e.setTopic(d.getTopic());
        e.setSummary(d.getSummary());
        e.setContent(d.getContent());
        e.setActorUserId(actorUserId);
        events.save(e);
    }

    private static String emptyIfNull(String s) {
        return s == null ? "" : s;
    }

    private Set<Long> collectUserIds(List<KnowledgeItemEntity> rows) {
        Set<Long> ids = new HashSet<>();
        for (KnowledgeItemEntity d : rows) {
            ids.add(d.getOwnerUserId());
            ids.add(d.getUpdatedBy());
        }
        return ids;
    }

    private Set<Long> collectActorIds(List<KnowledgeItemEventEntity> rows) {
        return rows.stream().map(KnowledgeItemEventEntity::getActorUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /** null 安全地收集用户 id（Set.of 不允许 null 元素，actor 可空）。 */
    private static Set<Long> idSet(Long... ids) {
        Set<Long> set = new HashSet<>();
        for (Long id : ids) {
            if (id != null) {
                set.add(id);
            }
        }
        return set;
    }

    /** 批量回填用户名（单条查询也走这里），displayName 优先回落 username，缺失用户留 null。 */
    private Map<Long, String> resolveUsernames(Set<Long> userIds) {
        Set<Long> ids = userIds == null ? Set.of() : userIds;
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new HashMap<>();
        for (UserEntity u : users.findAllById(ids)) {
            out.put(u.getId(), u.getDisplayName() != null && !u.getDisplayName().isBlank()
                    ? u.getDisplayName() : u.getUsername());
        }
        return out;
    }

    private static KnowledgeItemListItemDto toListItem(KnowledgeItemEntity d, Map<Long, String> names) {
        return new KnowledgeItemListItemDto(d.getId(), d.getProjectId(), d.getTopic(), d.getSummary(),
                d.getVersion(), d.getStatus(), d.getOwnerUserId(), names.get(d.getOwnerUserId()),
                d.getUpdatedBy(), names.get(d.getUpdatedBy()), ldt(d.getUpdatedAt()), d.getEmbeddingStatus());
    }

    private static KnowledgeItemDto toDto(KnowledgeItemEntity d, Map<Long, String> names) {
        return new KnowledgeItemDto(d.getId(), d.getProjectId(), d.getTopic(), d.getSummary(), d.getContent(),
                d.getVersion(), d.getStatus(), d.getOwnerUserId(), names.get(d.getOwnerUserId()),
                d.getUpdatedBy(), names.get(d.getUpdatedBy()), ldt(d.getCreatedAt()), ldt(d.getUpdatedAt()),
                d.getEmbeddingStatus());
    }

    private static LocalDateTime ldt(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
