package com.xinl.easyclaw.hub.service.knowledge;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.contract.spoke.SpokeKnowledgeEntry;
import com.xinl.easyclaw.hub.contract.spoke.SpokeKnowledgeEntryInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeKnowledgeUpsertRequest;
import com.xinl.easyclaw.hub.entity.KnowledgeItemEntity;
import com.xinl.easyclaw.hub.entity.KnowledgeItemEventEntity;
import com.xinl.easyclaw.hub.entity.ProjectEntity;
import com.xinl.easyclaw.hub.repository.KnowledgeItemEventRepository;
import com.xinl.easyclaw.hub.repository.KnowledgeItemRepository;
import com.xinl.easyclaw.hub.repository.ProjectRepository;
import com.xinl.easyclaw.hub.security.AppKeyContext;
import com.xinl.easyclaw.hub.service.AuditService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * spoke 工作区知识库云同步服务（V23 起统一落项目知识库 knowledge_items）：
 * spoke AI Agent 与 hub 平台用户按同一 (project_id, topic) 读写同一份数据，
 * hub 项目空间页「知识条目」与 spoke 侧知识库天然一致，不再有独立 workspace_knowledge 表。
 *
 * <p>Agent 写入语义 = 后写赢：条目存在即整体覆盖 summary/content（version+1、追加历史快照、
 * actor=null），<b>不校验乐观锁</b>——乐观锁仅约束 hub 平台用户的 Web 并发编辑；
 * 不存在即新建（owner_user_id/updated_by=0 占位，source=workspace）。
 * topic 项目内唯一（部分唯一索引兜底），upsert 语义天然归一多工作区同名条目。
 * 全库无外键，project 归属一致性在此校验（项目须存在且属于 appkey 组织）。
 */
@Service
public class WorkspaceKnowledgeService {

    private static final String STATUS_ACTIVE = "active";
    private static final String SOURCE_WORKSPACE = "workspace";
    /** Agent 写入的 user 占位（无 hub 用户；与迁移 V23 的存量行口径一致）。 */
    private static final long AGENT_USER_ID = 0L;

    private final KnowledgeItemRepository items;
    private final KnowledgeItemEventRepository events;
    private final ProjectRepository projects;
    private final AuditService auditService;

    public WorkspaceKnowledgeService(KnowledgeItemRepository items, KnowledgeItemEventRepository events,
                                     ProjectRepository projects, AuditService auditService) {
        this.items = items;
        this.events = events;
        this.projects = projects;
        this.auditService = auditService;
    }

    /** 某项目知识库条目清单（按 topic 升序）：lastModified = updated_at epoch 毫秒，fileSize = content 字节数（UTF-8）。 */
    public List<SpokeKnowledgeEntryInfo> entries(AppKeyContext ctx, Long projectId) {
        ProjectEntity p = loadProjectOfOrg(projectId, ctx.orgId());
        return items.findByProjectIdAndStatusOrderByUpdatedAtDesc(p.getId(), STATUS_ACTIVE).stream()
                .map(WorkspaceKnowledgeService::toInfo)
                .toList();
    }

    /** 单条内容（404 不存在；他组织项目一律 404 不暴露存在性）。 */
    public SpokeKnowledgeEntry entry(AppKeyContext ctx, Long projectId, String topic) {
        ProjectEntity p = loadProjectOfOrg(projectId, ctx.orgId());
        KnowledgeItemEntity e = items.findByProjectIdAndTopicAndStatus(p.getId(), topic, STATUS_ACTIVE)
                .orElseThrow(() -> ApiException.notFound("知识条目不存在"));
        auditService.record(AuditModule.KNOWLEDGE, "spoke_read_knowledge_entry", null, ctx.orgId(),
                "knowledge_item", String.valueOf(e.getId()), "projectId=" + projectId + ",topic=" + topic,
                AuditModule.SUCCESS);
        return new SpokeKnowledgeEntry(e.getTopic(), e.getContent());
    }

    /**
     * upsert：(project_id, topic) 存在即整体覆盖 summary/content（后写赢，version+1），否则新建。
     * 覆盖平台条目时来源转为 workspace（来源=当前内容写入方，与 updated_by 语义一致）。
     */
    @Transactional
    public SpokeKnowledgeEntryInfo upsert(AppKeyContext ctx, SpokeKnowledgeUpsertRequest req) {
        ProjectEntity p = loadProjectOfOrg(req.projectId(), ctx.orgId());
        String topic = req.topic();
        KnowledgeItemEntity e = items.findByProjectIdAndTopicAndStatus(p.getId(), topic, STATUS_ACTIVE)
                .orElseGet(() -> {
                    KnowledgeItemEntity n = new KnowledgeItemEntity();
                    n.setProjectId(p.getId());
                    n.setTopic(topic);
                    n.setStatus(STATUS_ACTIVE);
                    n.setOwnerUserId(AGENT_USER_ID);
                    n.setSource(SOURCE_WORKSPACE);
                    n.setSourceWorkspaceId(req.workspaceId());
                    return n;
                });
        boolean created = e.getId() == null;
        e.setSummary(req.summary() == null ? "" : req.summary());
        e.setContent(req.content() == null ? "" : req.content());
        e.setUpdatedBy(AGENT_USER_ID);
        if (created) {
            e.setVersion(1L);
        } else {
            e.setVersion(e.getVersion() + 1);
            e.setSource(SOURCE_WORKSPACE);
            e.setSourceWorkspaceId(req.workspaceId());
        }
        items.save(e);
        appendEvent(e);
        auditService.record(AuditModule.KNOWLEDGE,
                created ? "spoke_create_knowledge_entry" : "spoke_update_knowledge_entry",
                null, ctx.orgId(), "knowledge_item", String.valueOf(e.getId()),
                "workspaceId=" + req.workspaceId() + ",topic=" + topic + ",version=" + e.getVersion(),
                AuditModule.SUCCESS);
        return toInfo(e);
    }

    /** 追加历史快照（actor=null = Agent 写入，无 hub 用户）。 */
    private void appendEvent(KnowledgeItemEntity d) {
        KnowledgeItemEventEntity ev = new KnowledgeItemEventEntity();
        ev.setItemId(d.getId());
        ev.setProjectId(d.getProjectId());
        ev.setVersion(d.getVersion());
        ev.setTopic(d.getTopic());
        ev.setSummary(d.getSummary());
        ev.setContent(d.getContent());
        ev.setActorUserId(null);
        events.save(ev);
    }

    /** 项目须存在且属于 appkey 组织（他组织项目 404，不暴露存在性）。 */
    private ProjectEntity loadProjectOfOrg(Long projectId, Long orgId) {
        if (projectId == null) {
            throw ApiException.validation("projectId 必填：工作区须绑定 hub 项目后才能同步知识库");
        }
        ProjectEntity p = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("项目不存在"));
        if (!orgId.equals(p.getOrgId())) {
            throw ApiException.notFound("项目不存在");
        }
        return p;
    }

    private static SpokeKnowledgeEntryInfo toInfo(KnowledgeItemEntity e) {
        return new SpokeKnowledgeEntryInfo(e.getTopic(), e.getSummary(),
                e.getUpdatedAt() == null ? null : e.getUpdatedAt().toEpochMilli(),
                e.getContent() == null ? 0L : e.getContent().getBytes(StandardCharsets.UTF_8).length);
    }
}
