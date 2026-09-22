package com.xinl.easyclaw.ops.repository;

import com.xinl.easyclaw.ops.entity.OpsConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 运维连接配置数据访问接口
 */
@Repository
public interface OpsConnectionRepository extends JpaRepository<OpsConnectionEntity, Long> {

    List<OpsConnectionEntity> findByWorkspaceIdOrderByIdAsc(String workspaceId);

    Optional<OpsConnectionEntity> findByIdAndWorkspaceId(Long id, String workspaceId);

    boolean existsByWorkspaceIdAndName(String workspaceId, String name);
}
