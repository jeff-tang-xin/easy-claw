package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.OpsServerCategoryEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpsServerCategoryRepository extends JpaRepository<OpsServerCategoryEntity, Long> {

    List<OpsServerCategoryEntity> findAllByOrderBySortOrderAscIdAsc();

    boolean existsByName(String name);
}
