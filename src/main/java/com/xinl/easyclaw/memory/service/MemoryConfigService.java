package com.xinl.easyclaw.memory.service;

import com.xinl.easyclaw.memory.entity.MemoryConfigEntity;

import java.util.Optional;

/**
 * 记忆配置管理服务接口
 */
public interface MemoryConfigService {

    MemoryConfigEntity getOrCreate(String userId);

    MemoryConfigEntity update(String userId, MemoryConfigEntity config);

    Optional<MemoryConfigEntity> findByUserId(String userId);

    void delete(String userId);

    void resetToDefault(String userId);
}
