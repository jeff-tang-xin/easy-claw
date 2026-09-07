package com.xinl.easyclaw.scenario.repository;

import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 场景定义数据访问接口
 */
@Repository
public interface ScenarioRepository extends JpaRepository<ScenarioEntity, Long> {

    List<ScenarioEntity> findByActiveTrue();

    Optional<ScenarioEntity> findByName(String name);

    boolean existsByName(String name);
}
