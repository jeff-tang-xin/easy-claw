package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.PlatformToolEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformToolRepository extends JpaRepository<PlatformToolEntity, Long> {

    /** 按 sort_order、id 稳定排序。 */
    List<PlatformToolEntity> findAllByOrderBySortOrderAscIdAsc();

    Optional<PlatformToolEntity> findByToolKey(String toolKey);

    boolean existsByToolKey(String toolKey);
}
