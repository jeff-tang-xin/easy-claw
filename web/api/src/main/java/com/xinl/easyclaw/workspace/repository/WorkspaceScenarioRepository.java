package com.xinl.easyclaw.workspace.repository;

import com.xinl.easyclaw.workspace.entity.WorkspaceScenarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 工作区场景激活关系数据访问接口
 */
@Repository
public interface WorkspaceScenarioRepository extends JpaRepository<WorkspaceScenarioEntity, Long> {

    Optional<WorkspaceScenarioEntity> findByWorkspaceId(String workspaceId);

    List<WorkspaceScenarioEntity> findByScenarioId(Long scenarioId);

    void deleteByWorkspaceId(String workspaceId);
}
