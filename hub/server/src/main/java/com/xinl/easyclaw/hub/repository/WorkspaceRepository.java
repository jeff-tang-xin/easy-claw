package com.xinl.easyclaw.hub.repository;

import java.util.List;
import java.util.Optional;
import com.xinl.easyclaw.hub.entity.WorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

@Deprecated
public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, Long> {

    List<WorkspaceEntity> findByOrgId(Long orgId);

    /** 下发用：仅取组织下指定状态（active）的工作区。 */
    List<WorkspaceEntity> findByOrgIdAndStatus(Long orgId, String status);

    Optional<WorkspaceEntity> findByProjectId(Long projectId);

    boolean existsByProjectId(Long projectId);
}
