package com.xinl.easyclaw.workspace.repository;

import com.xinl.easyclaw.workspace.entity.WorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, String> {
    boolean existsByPath(String path);
    List<WorkspaceEntity> findByUserIdOrderByCreatedAtDesc(String userId);
    List<WorkspaceEntity> findByUserIdAndStatus(String userId, String status);

    /**
     * 启动迁移：历史版本的用户 ID（default-user）统一迁移为新规范值（local）。
     */
    @Modifying
    @Transactional
    @Query("UPDATE WorkspaceEntity w SET w.userId = :newId WHERE w.userId = :oldId")
    int migrateUserId(@Param("oldId") String oldId, @Param("newId") String newId);
}
