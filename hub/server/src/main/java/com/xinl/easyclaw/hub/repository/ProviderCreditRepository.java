package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.ProviderCreditEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderCreditRepository extends JpaRepository<ProviderCreditEntity, Long> {

    /** 该授权是否存在任意积分行（积分池启用判定之一：有临时积分但计划全关时仍启用）。 */
    boolean existsByGrantId(Long grantId);

    /** 当期积分是否已发放（惰性发放防重的快路径）。 */
    boolean existsByGrantIdAndPeriodTypeAndPeriodKey(Long grantId, String periodType, String periodKey);

    /**
     * 可消耗积分行（未耗尽且未过期），按过期时间升序（同刻按 id 兜底序）——
     * 「越早过期越先消耗」。consumed &lt; credits 是列间比较，派生查询表达不了，用 JPQL。
     * 行数通常个位数，Java 侧逐行 tryConsume 直到成功。
     */
    @Query("SELECT c FROM ProviderCreditEntity c WHERE c.grantId = :grantId "
            + "AND c.consumed < c.credits AND c.expiresAt > :now "
            + "ORDER BY c.expiresAt ASC, c.id ASC")
    List<ProviderCreditEntity> findConsumable(@Param("grantId") Long grantId, @Param("now") Instant now);

    /** 未过期积分行（剩余计算与 spoke 下发用；过期行不计入剩余）。 */
    List<ProviderCreditEntity> findByGrantIdAndExpiresAtAfter(Long grantId, Instant now);

    /**
     * 积分构成：按周期类型分组统计未过期剩余（Σ 面额 − 已消耗）。
     * 返回 [periodType, remaining] 二元组列表，缺失的周期类型即剩余 0。
     */
    @Query("SELECT c.periodType, SUM(c.credits - c.consumed) FROM ProviderCreditEntity c "
            + "WHERE c.grantId = :grantId AND c.expiresAt > :now GROUP BY c.periodType")
    List<Object[]> sumRemainingByPeriodType(@Param("grantId") Long grantId, @Param("now") Instant now);

    /** 管理端流水（含已过期/已耗尽，按发放时间倒序）。 */
    List<ProviderCreditEntity> findByGrantIdOrderByIdDesc(Long grantId);

    /** 多条授权的发放流水（组织/平台总览展开用）。 */
    List<ProviderCreditEntity> findByGrantIdInOrderByIdDesc(Collection<Long> grantIds);

    void deleteByGrantId(Long grantId);

    /**
     * 原子按量扣减（consumed + amount），带 consumed + amount &lt;= credits 守卫防并发超发；
     * 返回受影响行数：1 = 扣减成功，0 = 该行剩余不足或被并发扣完（调用方换下一行）。
     */
    @Modifying
    @Query("UPDATE ProviderCreditEntity c SET c.consumed = c.consumed + :amount "
            + "WHERE c.id = :id AND c.consumed + :amount <= c.credits")
    int tryConsumeAmount(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /**
     * 周期积分惰性发放（upsert 语义）：当期未发放则插入，已发放（唯一约束冲突）则忽略。
     * 网关热路径调用；ON CONFLICT 推断到 (grant_id, period_type, period_key) 唯一约束，
     * PostgreSQL 与 SQLite 测试环境均支持（同 {@code ProviderGrantUsageRepository.upsertIncrement}）。
     * temp 行 period_key 为 NULL，SQL 唯一约束对 NULL 不去重，不影响临时积分多笔发放。
     */
    @Modifying
    @Query(value = "INSERT INTO provider_credits (grant_id, period_type, period_key, credits, consumed, "
            + "expires_at, created_at, updated_at) "
            + "VALUES (:grantId, :periodType, :periodKey, :credits, 0, :expiresAt, "
            + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (grant_id, period_type, period_key) DO NOTHING",
            nativeQuery = true)
    int issuePeriodCredit(@Param("grantId") Long grantId, @Param("periodType") String periodType,
            @Param("periodKey") String periodKey, @Param("credits") BigDecimal credits,
            @Param("expiresAt") Instant expiresAt);
}
