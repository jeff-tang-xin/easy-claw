package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.ModelCatalogEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelCatalogRepository extends JpaRepository<ModelCatalogEntity, Long> {

    Optional<ModelCatalogEntity> findByModelName(String modelName);

    boolean existsByModelName(String modelName);

    List<ModelCatalogEntity> findAllByOrderByModelNameAsc();
}
