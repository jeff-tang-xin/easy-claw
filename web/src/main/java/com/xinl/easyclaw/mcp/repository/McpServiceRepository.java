package com.xinl.easyclaw.mcp.repository;

import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MCP 服务连接数据访问接口
 */
@Repository
public interface McpServiceRepository extends JpaRepository<McpServiceEntity, Long> {

    List<McpServiceEntity> findByIsConnectedTrue();

    List<McpServiceEntity> findByIsTemplateTrue();

    Optional<McpServiceEntity> findByName(String name);

    boolean existsByName(String name);

    List<McpServiceEntity> findByScope(String scope);

    Optional<McpServiceEntity> findByNameAndScope(String name, String scope);
}
