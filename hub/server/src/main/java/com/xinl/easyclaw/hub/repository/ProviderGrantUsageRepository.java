package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.ProviderGrantUsageEntity;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderGrantUsageRepository extends JpaRepository<ProviderGrantUsageEntity, Long> {

    Optional<ProviderGrantUsageEntity> findByGrantIdAndUsageDate(Long grantId, LocalDate usageDate);

    /**
     * 原子自增（upsert：无行插入 count=1，有行 +1），返回受影响行数。
     * 网关热路径调用：单条语句完成，避免「读-改-写」并发丢计数。
     * 时间用 CURRENT_TIMESTAMP（PostgreSQL 与 SQLite 测试环境均支持；now() 仅 PG 有）。
     */
    @Modifying
    @Query(value = "INSERT INTO provider_grant_usage (grant_id, usage_date, request_count, created_at, updated_at) "
            + "VALUES (:grantId, :date, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (grant_id, usage_date) "
            + "DO UPDATE SET request_count = provider_grant_usage.request_count + 1, updated_at = CURRENT_TIMESTAMP",
            nativeQuery = true)
    int upsertIncrement(@Param("grantId") Long grantId, @Param("date") LocalDate date);

    void deleteByGrantId(Long grantId);
}
