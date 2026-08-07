package com.xinl.easyclaw.memory.repository;

import com.xinl.easyclaw.memory.entity.MemoryConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 记忆配置数据访问接口
 */
@Repository
public interface MemoryConfigRepository extends JpaRepository<MemoryConfigEntity, Long> {

    Optional<MemoryConfigEntity> findByUserId(String userId);

    boolean existsByUserId(String userId);

    void deleteByUserId(String userId);
}
