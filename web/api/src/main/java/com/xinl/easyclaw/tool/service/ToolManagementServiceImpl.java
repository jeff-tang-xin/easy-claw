package com.xinl.easyclaw.tool.service;

import com.xinl.easyclaw.tool.entity.ToolDefinitionEntity;
import com.xinl.easyclaw.tool.repository.ToolDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 工具管理服务实现
 * <p>
 * 提供工具/插件的 CRUD 操作，支持启用/禁用、参数编辑、测试调用等功能
 */
@Service
public class ToolManagementServiceImpl implements ToolManagementService {

    private static final Logger log = LoggerFactory.getLogger(ToolManagementServiceImpl.class);

    private final ToolDefinitionRepository repository;

    public ToolManagementServiceImpl(ToolDefinitionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public ToolDefinitionEntity create(ToolDefinitionEntity tool) {
        if (repository.existsByName(tool.getName())) {
            throw new IllegalArgumentException("工具名称已存在: " + tool.getName());
        }
        ToolDefinitionEntity saved = repository.save(tool);
        log.info("创建工具: name={}, group={}", tool.getName(), tool.getToolGroup());
        return saved;
    }

    @Override
    @Transactional
    public ToolDefinitionEntity update(Long id, ToolDefinitionEntity tool) {
        return repository.findById(id)
                .map(existing -> {
                    existing.setDisplayName(tool.getDisplayName());
                    existing.setDescription(tool.getDescription());
                    existing.setToolGroup(tool.getToolGroup());
                    existing.setParameters(tool.getParameters());
                    existing.setImplementationConfig(tool.getImplementationConfig());
                    if (tool.getEnabled() != null) {
                        existing.setEnabled(tool.getEnabled());
                    }
                    ToolDefinitionEntity updated = repository.save(existing);
                    log.info("更新工具: id={}, name={}", id, existing.getName());
                    return updated;
                })
                .orElseThrow(() -> new IllegalArgumentException("工具不存在: id=" + id));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("工具不存在: id=" + id);
        }
        repository.deleteById(id);
        log.info("删除工具: id={}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ToolDefinitionEntity> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ToolDefinitionEntity> findByName(String name) {
        return repository.findByName(name);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ToolDefinitionEntity> findAll() {
        return repository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ToolDefinitionEntity> findEnabledTools() {
        return repository.findByEnabledTrue();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ToolDefinitionEntity> findByGroup(String toolGroup) {
        return repository.findByToolGroup(toolGroup);
    }

    @Override
    @Transactional
    public ToolDefinitionEntity setEnabled(Long id, boolean enabled) {
        return repository.findById(id)
                .map(existing -> {
                    existing.setEnabled(enabled);
                    ToolDefinitionEntity updated = repository.save(existing);
                    log.info("设置工具启用状态: id={}, enabled={}", id, enabled);
                    return updated;
                })
                .orElseThrow(() -> new IllegalArgumentException("工具不存在: id=" + id));
    }
}
