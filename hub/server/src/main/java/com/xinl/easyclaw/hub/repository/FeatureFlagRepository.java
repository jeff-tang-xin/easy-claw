package com.xinl.easyclaw.hub.repository;

import java.util.List;
import java.util.Optional;
import com.xinl.easyclaw.hub.entity.FeatureFlagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlagEntity, Long> {

    /** 按 sort_order、id 稳定排序。 */
    List<FeatureFlagEntity> findAllByOrderBySortOrderAscIdAsc();

    Optional<FeatureFlagEntity> findByFlagKey(String flagKey);

    boolean existsByFlagKey(String flagKey);
}
