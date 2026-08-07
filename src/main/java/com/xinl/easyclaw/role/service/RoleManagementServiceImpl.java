package com.xinl.easyclaw.role.service;

import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import com.xinl.easyclaw.role.repository.AgentRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 角色管理服务实现
 * <p>
 * 提供角色的 CRUD 操作，支持通过页面实时配置角色定义，热加载生效
 */
@Service
public class RoleManagementServiceImpl implements RoleManagementService {

    private static final Logger log = LoggerFactory.getLogger(RoleManagementServiceImpl.class);

    private final AgentRoleRepository repository;

    public RoleManagementServiceImpl(AgentRoleRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public AgentRoleEntity create(AgentRoleEntity role) {
        if (repository.existsByName(role.getName())) {
            throw new IllegalArgumentException("角色名称已存在: " + role.getName());
        }
        AgentRoleEntity saved = repository.save(role);
        log.info("创建角色: name={}", role.getName());
        return saved;
    }

    @Override
    @Transactional
    public AgentRoleEntity update(Long id, AgentRoleEntity role) {
        return repository.findById(id)
                .map(existing -> {
                    existing.setDisplayName(role.getDisplayName());
                    existing.setRole(role.getRole());
                    existing.setGoal(role.getGoal());
                    existing.setBackstory(role.getBackstory());
                    existing.setTemperature(role.getTemperature());
                    existing.setModel(role.getModel());
                    if (role.getActive() != null) {
                        existing.setActive(role.getActive());
                    }
                    AgentRoleEntity updated = repository.save(existing);
                    log.info("更新角色: id={}, name={}", id, existing.getName());
                    return updated;
                })
                .orElseThrow(() -> new IllegalArgumentException("角色不存在: id=" + id));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("角色不存在: id=" + id);
        }
        repository.deleteById(id);
        log.info("删除角色: id={}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRoleEntity> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRoleEntity> findByName(String name) {
        return repository.findByName(name);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentRoleEntity> findAll() {
        return repository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentRoleEntity> findActiveRoles() {
        return repository.findByActiveTrue();
    }

    @Override
    @Transactional
    public AgentRoleEntity setActive(Long id, boolean active) {
        return repository.findById(id)
                .map(existing -> {
                    existing.setActive(active);
                    AgentRoleEntity updated = repository.save(existing);
                    log.info("设置角色激活状态: id={}, active={}", id, active);
                    return updated;
                })
                .orElseThrow(() -> new IllegalArgumentException("角色不存在: id=" + id));
    }
}
