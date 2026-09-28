package com.xinl.easyclaw.memory.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 记忆设置仓库：按 userId 单条存取。
 */
@Repository
public interface MemorySettingsRepository extends JpaRepository<MemorySettingsEntity, Long> {

    Optional<MemorySettingsEntity> findByUserId(String userId);
}
