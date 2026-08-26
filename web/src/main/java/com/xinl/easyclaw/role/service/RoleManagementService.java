package com.xinl.easyclaw.role.service;

import com.xinl.easyclaw.role.entity.AgentRoleEntity;

import java.util.List;
import java.util.Optional;

/**
 * 角色管理服务接口
 */
public interface RoleManagementService {

    AgentRoleEntity create(AgentRoleEntity role);

    AgentRoleEntity update(Long id, AgentRoleEntity role);

    void delete(Long id);

    Optional<AgentRoleEntity> findById(Long id);

    Optional<AgentRoleEntity> findByName(String name);

    List<AgentRoleEntity> findAll();

    List<AgentRoleEntity> findActiveRoles();

    AgentRoleEntity setActive(Long id, boolean active);
}
