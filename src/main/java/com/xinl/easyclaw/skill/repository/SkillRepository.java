package com.xinl.easyclaw.skill.repository;

import com.xinl.easyclaw.skill.entity.SkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SkillRepository extends JpaRepository<SkillEntity, Long> {
    List<SkillEntity> findByScope(String scope);
    List<SkillEntity> findByScopeAndWorkspaceId(String scope, String workspaceId);
    Optional<SkillEntity> findByNameAndScope(String name, String scope);
    Optional<SkillEntity> findByNameAndScopeAndWorkspaceId(String name, String scope, String workspaceId);
    boolean existsByNameAndScope(String name, String scope);
}
