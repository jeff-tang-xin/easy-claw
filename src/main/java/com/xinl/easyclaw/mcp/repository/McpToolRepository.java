package com.xinl.easyclaw.mcp.repository;

import com.xinl.easyclaw.mcp.entity.McpToolEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface McpToolRepository extends JpaRepository<McpToolEntity, Long> {

    List<McpToolEntity> findByServiceIdOrderBySortOrderAsc(Long serviceId);

    List<McpToolEntity> findByServiceIdAndEnabledTrueOrderBySortOrderAsc(Long serviceId);

    Optional<McpToolEntity> findByToolNameAndServiceName(String toolName, String serviceName);

    void deleteByServiceId(Long serviceId);
}
