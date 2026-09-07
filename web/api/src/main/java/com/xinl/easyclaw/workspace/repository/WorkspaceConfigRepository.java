package com.xinl.easyclaw.workspace.repository;

import com.xinl.easyclaw.workspace.entity.WorkspaceConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkspaceConfigRepository extends JpaRepository<WorkspaceConfigEntity, Long> {
    List<WorkspaceConfigEntity> findByWorkspaceId(String workspaceId);
    Optional<WorkspaceConfigEntity> findByWorkspaceIdAndConfigTypeAndConfigKey(
            String workspaceId, String configType, String configKey);
    void deleteByWorkspaceId(String workspaceId);
}
