package com.xinl.easyclaw.hub.service.ops;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.contract.ops.CreateOpsServerCategoryRequest;
import com.xinl.easyclaw.hub.contract.ops.OpsServerCategoryDto;
import com.xinl.easyclaw.hub.contract.ops.UpdateOpsServerCategoryRequest;
import com.xinl.easyclaw.hub.entity.OpsServerCategoryEntity;
import com.xinl.easyclaw.hub.repository.OpsServerCategoryRepository;
import com.xinl.easyclaw.hub.repository.OpsServerRepository;
import com.xinl.easyclaw.hub.service.AuditService;
import com.xinl.easyclaw.hub.service.PlatformAdminGuard;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运维服务器分类标签字典服务（ops_server_categories，V28）：hub 统一维护（platformAdmin CRUD）。
 * 服务器目录（ops_servers.category）收口为受管标签：重命名同步引用行（事务内）；
 * 被服务器引用的标签禁止删除（409，避免服务器退回未标注）。
 */
@Service
public class OpsServerCategoryService {

    private final OpsServerCategoryRepository repo;
    private final OpsServerRepository servers;
    private final PlatformAdminGuard guard;
    private final AuditService auditService;

    public OpsServerCategoryService(OpsServerCategoryRepository repo, OpsServerRepository servers,
                                    PlatformAdminGuard guard, AuditService auditService) {
        this.repo = repo;
        this.servers = servers;
        this.guard = guard;
        this.auditService = auditService;
    }

    /** 标签清单（platformAdmin），按 sort_order,id 保序。 */
    public List<OpsServerCategoryDto> listCatalog(Long requesterId) {
        guard.require(requesterId);
        return repo.findAllByOrderBySortOrderAscIdAsc().stream().map(OpsServerCategoryService::toDto).toList();
    }

    /** 新增标签（platformAdmin）：name 全局唯一（409）。 */
    @Transactional
    public OpsServerCategoryDto createCatalogItem(Long requesterId, CreateOpsServerCategoryRequest req) {
        guard.require(requesterId);
        String name = req.name().trim();
        if (repo.existsByName(name)) {
            throw ApiException.conflict("分类标签已存在");
        }
        OpsServerCategoryEntity e = new OpsServerCategoryEntity();
        e.setName(name);
        e.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        repo.save(e);
        auditService.record(AuditModule.OPS, "create_ops_server_category", requesterId, null,
                "ops_server_category", String.valueOf(e.getId()), "name=" + name, AuditModule.SUCCESS);
        return toDto(e);
    }

    /**
     * 更新标签（platformAdmin）：字段全可空 = 不传不改；name 重命名时同步 ops_servers.category
     * 引用行（事务内，服务器目录不出现悬空标签）；新名与其他标签冲突 → 409。
     */
    @Transactional
    public OpsServerCategoryDto updateCatalogItem(Long requesterId, Long id, UpdateOpsServerCategoryRequest req) {
        guard.require(requesterId);
        OpsServerCategoryEntity e = repo.findById(id)
                .orElseThrow(() -> ApiException.notFound("分类标签不存在"));
        if (req.name() != null) {
            String name = req.name().trim();
            if (!name.equals(e.getName())) {
                if (repo.existsByName(name)) {
                    throw ApiException.conflict("分类标签已存在");
                }
                String oldName = e.getName();
                e.setName(name);
                servers.findAllByCategory(oldName).forEach(s -> {
                    s.setCategory(name);
                    servers.save(s);
                });
            }
        }
        if (req.sortOrder() != null) {
            e.setSortOrder(req.sortOrder());
        }
        repo.save(e);
        auditService.record(AuditModule.OPS, "update_ops_server_category", requesterId, null,
                "ops_server_category", String.valueOf(e.getId()), "name=" + e.getName(), AuditModule.SUCCESS);
        return toDto(e);
    }

    /** 删除标签（platformAdmin）：被服务器引用时 409（先改服务器分类再删）。 */
    @Transactional
    public void deleteCatalogItem(Long requesterId, Long id) {
        guard.require(requesterId);
        OpsServerCategoryEntity e = repo.findById(id)
                .orElseThrow(() -> ApiException.notFound("分类标签不存在"));
        if (servers.existsByCategory(e.getName())) {
            throw ApiException.conflict("分类标签仍被服务器引用，请先调整相关服务器的分类");
        }
        repo.delete(e);
        auditService.record(AuditModule.OPS, "delete_ops_server_category", requesterId, null,
                "ops_server_category", String.valueOf(id), "name=" + e.getName(), AuditModule.SUCCESS);
    }

    private static OpsServerCategoryDto toDto(OpsServerCategoryEntity e) {
        return new OpsServerCategoryDto(e.getId(), e.getName(), e.getSortOrder());
    }
}
