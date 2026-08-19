package com.xinl.easyclaw.scenario.repository;

import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScenarioRepository extends JpaRepository<ScenarioEntity, String> {

    List<ScenarioEntity> findByOwnerId(String ownerId);

    List<ScenarioEntity> findByIsPresetTrue();

    List<ScenarioEntity> findByOwnerIdOrIsPresetTrue(String ownerId);
}
