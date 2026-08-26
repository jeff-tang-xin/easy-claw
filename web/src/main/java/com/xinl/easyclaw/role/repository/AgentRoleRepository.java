package com.xinl.easyclaw.role.repository;

import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 角色定义数据访问接口
 */
@Repository
public interface AgentRoleRepository extends JpaRepository<AgentRoleEntity, Long> {

    List<AgentRoleEntity> findByActiveTrue();

    Optional<AgentRoleEntity> findByName(String name);

    boolean existsByName(String name);
}
