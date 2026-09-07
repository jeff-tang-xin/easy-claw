package com.xinl.easyclaw.tool.service;

import com.xinl.easyclaw.tool.entity.ToolDefinitionEntity;

import java.util.List;
import java.util.Optional;

/**
 * 工具管理服务接口
 */
public interface ToolManagementService {

    ToolDefinitionEntity create(ToolDefinitionEntity tool);

    ToolDefinitionEntity update(Long id, ToolDefinitionEntity tool);

    void delete(Long id);

    Optional<ToolDefinitionEntity> findById(Long id);

    Optional<ToolDefinitionEntity> findByName(String name);

    List<ToolDefinitionEntity> findAll();

    List<ToolDefinitionEntity> findEnabledTools();

    List<ToolDefinitionEntity> findByGroup(String toolGroup);

    ToolDefinitionEntity setEnabled(Long id, boolean enabled);
}
