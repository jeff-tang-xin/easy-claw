package com.xinl.easyclaw.capability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 智能体能力绑定仓库 */
public interface AgentCapabilityRepository extends JpaRepository<AgentCapabilityEntity, Long> {

    List<AgentCapabilityEntity> findByAgentIdOrderByKindAscNameAsc(String agentId);

    List<AgentCapabilityEntity> findByAgentIdAndKindOrderByEnabledDescNameAsc(String agentId, String kind);

    List<AgentCapabilityEntity> findByAgentIdInAndKind(Collection<String> agentIds, String kind);

    Optional<AgentCapabilityEntity> findByAgentIdAndKindAndName(String agentId, String kind, String name);

    void deleteByAgentIdAndKind(String agentId, String kind);
}
