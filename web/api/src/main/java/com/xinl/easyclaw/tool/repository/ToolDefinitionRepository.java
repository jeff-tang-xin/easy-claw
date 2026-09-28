package com.xinl.easyclaw.tool.repository;

import com.xinl.easyclaw.tool.entity.ToolDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 工具/插件定义数据访问接口
 */
@Repository
public interface ToolDefinitionRepository extends JpaRepository<ToolDefinitionEntity, Long> {

    List<ToolDefinitionEntity> findByEnabledTrue();

    List<ToolDefinitionEntity> findByToolGroup(String toolGroup);

    List<ToolDefinitionEntity> findByImplementation(String implementation);

    Optional<ToolDefinitionEntity> findByName(String name);

    boolean existsByName(String name);
}
