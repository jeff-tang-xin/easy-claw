package com.xinl.easyclaw.hub.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.xinl.easyclaw.hub.entity.OrganizationEntity;

public interface OrganizationRepository extends JpaRepository<OrganizationEntity, Long> {

    Optional<OrganizationEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
