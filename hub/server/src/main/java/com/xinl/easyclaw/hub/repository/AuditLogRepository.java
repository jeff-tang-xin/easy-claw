package com.xinl.easyclaw.hub.repository;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import com.xinl.easyclaw.hub.controller.AuditLogController;
import com.xinl.easyclaw.hub.entity.AuditLogEntity;

/**
 * 审计日志仓库。组织维度分页 finder 供控制台审计页（{@link AuditLogController}）使用。
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    List<AuditLogEntity> findByModuleOrderByCreatedAtDesc(String module);

    List<AuditLogEntity> findByOrgIdOrderByCreatedAtDesc(Long orgId);

    List<AuditLogEntity> findByOrgIdOrderByCreatedAtDesc(Long orgId, Pageable pageable);

    List<AuditLogEntity> findByOrgIdAndModuleOrderByCreatedAtDesc(Long orgId, String module, Pageable pageable);

    long countByOrgId(Long orgId);

    long countByOrgIdAndModule(Long orgId, String module);

    List<AuditLogEntity> findByActorUserIdOrderByCreatedAtDesc(Long actorUserId);
}
