package com.xinl.easyclaw.permission.repository;

import com.xinl.easyclaw.permission.entity.PermissionRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 工具权限规则数据访问接口（按 Workspace 隔离）
 */
@Repository
public interface PermissionRuleRepository extends JpaRepository<PermissionRuleEntity, Long> {

    Optional<PermissionRuleEntity> findByWorkspaceIdAndToolName(String workspaceId, String toolName);

    List<PermissionRuleEntity> findByWorkspaceIdOrderByCreatedAtAsc(String workspaceId);

    @Modifying
    @Transactional
    @Query("DELETE FROM PermissionRuleEntity p WHERE p.workspaceId = ?1 AND p.toolName = ?2")
    void deleteByWorkspaceIdAndToolName(String workspaceId, String toolName);

    @Modifying
    @Transactional
    @Query("DELETE FROM PermissionRuleEntity p WHERE p.workspaceId = ?1")
    int deleteByWorkspaceId(String workspaceId);
}
