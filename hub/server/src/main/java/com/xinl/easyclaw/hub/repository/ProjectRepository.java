package com.xinl.easyclaw.hub.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.xinl.easyclaw.hub.entity.ProjectEntity;

public interface ProjectRepository extends JpaRepository<ProjectEntity, Long> {

    List<ProjectEntity> findByOrgId(Long orgId);

    List<ProjectEntity> findByOrgIdAndStatus(Long orgId, String status);

    Optional<ProjectEntity> findByOrgIdAndSlug(Long orgId, String slug);

    boolean existsByOrgIdAndSlug(Long orgId, String slug);
}
