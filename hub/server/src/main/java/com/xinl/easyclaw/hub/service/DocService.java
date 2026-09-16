package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.contract.docs.AssignDocRequest;
import com.xinl.easyclaw.hub.contract.docs.CreateDocRequest;
import com.xinl.easyclaw.hub.contract.docs.DocDto;
import com.xinl.easyclaw.hub.contract.docs.DocEventDto;
import com.xinl.easyclaw.hub.contract.docs.DocListItemDto;
import com.xinl.easyclaw.hub.contract.docs.UpdateDocRequest;
import com.xinl.easyclaw.hub.entity.DocEntity;
import com.xinl.easyclaw.hub.entity.DocEventEntity;
import com.xinl.easyclaw.hub.entity.ProjectEntity;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.DocEventRepository;
import com.xinl.easyclaw.hub.repository.DocRepository;
import com.xinl.easyclaw.hub.repository.ProjectRepository;
import com.xinl.easyclaw.hub.repository.UserRepository;
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
import jakarta.persistence.EntityManager;

/**
 * 项目内协作文档（需求/任务）服务，A2。
 *
 * <p>权限与项目可见性对齐：读取按 project 的 private/team/public + 组织角色（同 {@link ProjectService} 口径）；
 * 写入需组织成员（guest 只读）；删除/改派仅限组织 owner/admin 或文档创建者。
 *
 * <p>并发：更新走 {@code UPDATE ... WHERE id=? AND version=?} 乐观锁，0 行即版本冲突，
 * 抛 409 且 details 携带最新文档快照，客户端提示手动合并（不做自动合并/CRDT）。
 * 每次创建/更新在 doc_events 追加一行不可变快照。无外键（V4 决策），硬删文档保留历史。
 */
@Service
public class DocService {

    private static final Set<String> VALID_DOC_TYPE = Set.of("requirement", "task");
    private static final String TYPE_TASK = "task";

    private final DocRepository docs;
    private final DocEventRepository events;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final OrgService orgService;
    private final AuditService auditService;
    private final EntityManager em;

    public DocService(DocRepository docs, DocEventRepository events, ProjectRepository projects,
                      UserRepository users, OrgService orgService, AuditService auditService,
                      EntityManager em) {
        this.docs = docs;
        this.events = events;
        this.projects = projects;
        this.users = users;
        this.orgService = orgService;
        this.auditService = auditService;
        this.em = em;
    }

    // ---------- 查询 ----------

    public List<DocListItemDto> list(Long requesterId, Long projectId, String docType) {
        ProjectEntity p = loadProject(projectId);
        requireCanRead(p, requesterId);
        List<DocEntity> rows = docType == null || docType.isBlank()
                ? docs.findByProjectIdOrderByIdDesc(projectId)
                : docs.findByProjectIdAndDocTypeOrderByIdDesc(projectId, validateType(docType));
        Map<Long, String> names = resolveUsernames(collectUserIds(rows));
        return rows.stream().map(d -> toListItem(d, names)).toList();
    }

    public DocDto get(Long requesterId, Long docId) {
        DocEntity d = loadDoc(docId);
        ProjectEntity p = loadProject(d.getProjectId());
        requireCanRead(p, requesterId);
        return toDto(d, resolveUsernames(idSet(d.getOwnerUserId(), d.getAssigneeUserId())));
    }

    public List<DocEventDto> history(Long requesterId, Long docId) {
        // 不依赖 docs 表：文档硬删后历史仍要可查；项目 id 从事件行冗余列取（首行足够，同 doc 同 project）。
        List<DocEventEntity> rows = events.findByDocIdOrderByVersionDesc(docId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("文档不存在");
        }
        ProjectEntity p = loadProject(rows.get(0).getProjectId());
        requireCanRead(p, requesterId);
        Set<Long> actorIds = rows.stream().map(DocEventEntity::getActorUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = resolveUsernames(actorIds);
        return rows.stream().map(e -> new DocEventDto(e.getId(), e.getDocId(), e.getVersion(), e.getTitle(),
                e.getContent(), e.getActorUserId(), names.get(e.getActorUserId()), ldt(e.getCreatedAt()))).toList();
    }

    // ---------- 写入 ----------

    @Transactional
    public DocDto create(Long requesterId, CreateDocRequest req) {
        ProjectEntity p = loadProject(req.projectId());
        requireCanWrite(p, requesterId);
        String type = req.docType() == null || req.docType().isBlank() ? "requirement" : validateType(req.docType());
        validateParent(req.parentDocId(), p.getId(), type);
        if (req.title() == null || req.title().isBlank()) {
            throw ApiException.validation("标题不能为空");
        }

        DocEntity d = new DocEntity();
        d.setProjectId(p.getId());
        d.setParentDocId(req.parentDocId());
        d.setTitle(req.title().trim());
        d.setContent(req.content() == null ? "" : req.content());
        d.setDocType(type);
        d.setStatus("active");
        d.setOwnerUserId(requesterId);
        d.setVersion(1L);
        docs.save(d);
        appendEvent(d, requesterId);
        auditService.record(AuditModule.DOCS, "create_doc", requesterId, p.getOrgId(), "doc",
                String.valueOf(d.getId()), "type=" + type + ",title=" + d.getTitle(), AuditModule.SUCCESS);
        return toDto(d, resolveUsernames(idSet(d.getOwnerUserId(), d.getAssigneeUserId())));
    }

    @Transactional
    public DocDto update(Long requesterId, Long docId, UpdateDocRequest req) {
        DocEntity d = loadDoc(docId);
        ProjectEntity p = loadProject(d.getProjectId());
        requireCanWrite(p, requesterId);

        boolean hasTitle = req.title() != null && !req.title().isBlank();
        boolean hasContent = req.content() != null;
        if (!hasTitle && !hasContent) {
            throw ApiException.validation("标题与内容至少提供一项");
        }
        String newTitle = hasTitle ? req.title().trim() : d.getTitle();
        String newContent = hasContent ? req.content() : d.getContent();
        Long expected = req.expectedVersion() == null ? d.getVersion() : req.expectedVersion();

        // 条件更新：version 未被他人推进才成功；清空持久化上下文后重读，拿到 DB 真实状态（含新 version）。
        int affected = docs.updateContentIfVersion(d.getId(), newTitle, newContent, expected);
        if (affected == 0) {
            docs.flush();
            DocEntity latest = loadDoc(docId);
            Map<Long, String> names = resolveUsernames(
                    idSet(latest.getOwnerUserId(), latest.getAssigneeUserId()));
            throw ApiException.conflict("文档已被他人更新，请合并后再保存（你的编辑区内容不会被覆盖）", toDto(latest, names));
        }
        docs.flush();
        em.detach(d);
        DocEntity saved = loadDoc(docId);
        appendEvent(saved, requesterId);
        auditService.record(AuditModule.DOCS, "update_doc", requesterId, p.getOrgId(), "doc",
                String.valueOf(docId), "version=" + saved.getVersion(), AuditModule.SUCCESS);
        return toDto(saved, resolveUsernames(idSet(saved.getOwnerUserId(), saved.getAssigneeUserId())));
    }

    @Transactional
    public DocDto assign(Long requesterId, Long docId, AssignDocRequest req) {
        DocEntity d = loadDoc(docId);
        ProjectEntity p = loadProject(d.getProjectId());
        requireCanManage(d, p, requesterId);
        if (req.assigneeUserId() != null) {
            String assigneeRole = orgService.roleOf(p.getOrgId(), req.assigneeUserId());
            if (assigneeRole == null) {
                throw ApiException.validation("负责人必须是本组织成员");
            }
            if ("guest".equals(assigneeRole)) {
                throw ApiException.validation("访客为只读角色，不能作为负责人");
            }
        }
        d.setAssigneeUserId(req.assigneeUserId());
        docs.save(d);
        auditService.record(AuditModule.DOCS, "assign_doc", requesterId, p.getOrgId(), "doc",
                String.valueOf(docId), "assignee=" + req.assigneeUserId(), AuditModule.SUCCESS);
        return toDto(d, resolveUsernames(idSet(d.getOwnerUserId(), d.getAssigneeUserId())));
    }

    @Transactional
    public void delete(Long requesterId, Long docId) {
        DocEntity d = loadDoc(docId);
        ProjectEntity p = loadProject(d.getProjectId());
        requireCanManage(d, p, requesterId);
        docs.delete(d);
        // 历史 doc_events 为追加留痕，无外键级联，刻意保留。
        auditService.record(AuditModule.DOCS, "delete_doc", requesterId, p.getOrgId(), "doc",
                String.valueOf(docId), "title=" + d.getTitle(), AuditModule.SUCCESS);
    }

    // ---------- 权限 ----------

    /**
     * 读：与 {@link ProjectService#get} 同口径——public 组织外可读；team/private 需组织成员，
     * 且成员须能看到该项目（private 仅 owner/admin + 项目创建者）。
     */
    private void requireCanRead(ProjectEntity p, Long requesterId) {
        if (!canSeeProject(p, requesterId)) {
            throw ApiException.forbidden("无权限查看该项目的文档");
        }
    }

    /**
     * 写：先须能看到该项目（收紧 private 越权写），再要求可编辑角色——
     * guest 只读、非成员不可写。
     */
    private void requireCanWrite(ProjectEntity p, Long requesterId) {
        if (!canSeeProject(p, requesterId)) {
            throw ApiException.forbidden("无权限操作该项目的文档");
        }
        String role = orgService.roleOf(p.getOrgId(), requesterId);
        if ("guest".equals(role)) {
            throw ApiException.forbidden("访客只读，不能编辑文档");
        }
    }

    /**
     * 项目可见性，口径与 {@link ProjectService} 的 get/canSee 完全一致：
     * public 对所有登录用户可读；非 public 须为组织成员；成员中 private 仅 owner/admin + 项目创建者。
     */
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

    /** 管理（删除/改派）：组织 owner/admin 或文档创建者。 */
    private void requireCanManage(DocEntity d, ProjectEntity p, Long requesterId) {
        String role = orgService.roleOf(p.getOrgId(), requesterId);
        boolean canManage = "owner".equals(role) || "admin".equals(role)
                || d.getOwnerUserId().equals(requesterId);
        if (!canManage) {
            throw ApiException.forbidden("无权限管理该文档");
        }
    }

    // ---------- 辅助 ----------

    private ProjectEntity loadProject(Long projectId) {
        return projects.findById(projectId).orElseThrow(() -> ApiException.notFound("项目不存在"));
    }

    private DocEntity loadDoc(Long docId) {
        return docs.findById(docId).orElseThrow(() -> ApiException.notFound("文档不存在"));
    }

    private String validateType(String type) {
        if (!VALID_DOC_TYPE.contains(type)) {
            throw ApiException.validation("非法文档类型：" + type);
        }
        return type;
    }

    /** task 必须挂父文档，且父文档属于同一项目（不强制父必须是 requirement，保持简单）。 */
    private void validateParent(Long parentDocId, Long projectId, String type) {
        if (parentDocId == null) {
            return;
        }
        if (TYPE_TASK.equals(type)) {
            DocEntity parent = loadDoc(parentDocId);
            if (!projectId.equals(parent.getProjectId())) {
                throw ApiException.validation("父文档必须属于同一项目");
            }
        }
    }

    private void appendEvent(DocEntity d, Long actorUserId) {
        DocEventEntity e = new DocEventEntity();
        e.setDocId(d.getId());
        e.setProjectId(d.getProjectId());
        e.setVersion(d.getVersion());
        e.setTitle(d.getTitle());
        e.setContent(d.getContent());
        e.setActorUserId(actorUserId);
        events.save(e);
    }

    private Set<Long> collectUserIds(List<DocEntity> rows) {
        Set<Long> ids = new HashSet<>();
        for (DocEntity d : rows) {
            ids.add(d.getOwnerUserId());
            if (d.getAssigneeUserId() != null) {
                ids.add(d.getAssigneeUserId());
            }
        }
        return ids;
    }

    /** null 安全地收集用户 id（Set.of 不允许 null 元素，assignee 可空）。 */
    private static Set<Long> idSet(Long... ids) {
        Set<Long> set = new HashSet<>();
        for (Long id : ids) {
            if (id != null) {
                set.add(id);
            }
        }
        return set;
    }

    /** 批量回填用户名（单条查询也走这里），缺失用户留 null。 */
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

    private static DocListItemDto toListItem(DocEntity d, Map<Long, String> names) {
        return new DocListItemDto(d.getId(), d.getProjectId(), d.getParentDocId(), d.getTitle(),
                d.getDocType(), d.getStatus(), d.getOwnerUserId(), names.get(d.getOwnerUserId()),
                d.getAssigneeUserId(), names.get(d.getAssigneeUserId()), d.getVersion(), ldt(d.getUpdatedAt()));
    }

    private static DocDto toDto(DocEntity d, Map<Long, String> names) {
        return new DocDto(d.getId(), d.getProjectId(), d.getParentDocId(), d.getTitle(), d.getDocType(),
                d.getStatus(), d.getOwnerUserId(), names.get(d.getOwnerUserId()), d.getAssigneeUserId(),
                names.get(d.getAssigneeUserId()), d.getVersion(), d.getContent(),
                ldt(d.getCreatedAt()), ldt(d.getUpdatedAt()));
    }

    private static LocalDateTime ldt(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
