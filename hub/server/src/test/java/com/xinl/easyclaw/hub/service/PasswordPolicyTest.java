package com.xinl.easyclaw.hub.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码有效期策略纯时间计算单测：60 天有效期、到期前 7 天临期窗口、null 起算点。
 * 固定参考时刻，精确到天边界，不依赖系统时钟。
 */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();
    private final Instant changedAt = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant expiresAt = changedAt.plus(60, ChronoUnit.DAYS); // 2026-03-02T00:00:00Z

    @Test
    void expiresAt_isExactlySixtyDaysAfterChange() {
        assertEquals(expiresAt, policy.expiresAt(changedAt));
    }

    @Test
    void freshPassword_isNeitherExpiredNorExpiringSoon() {
        Instant now = changedAt.plus(1, ChronoUnit.DAYS);
        assertFalse(policy.isExpired(changedAt, now));
        assertFalse(policy.isExpiringSoon(changedAt, now));
    }

    @Test
    void exactlySevenDaysBeforeExpiry_isNotYetExpiringSoon() {
        // 剩余恰好 7 天：now+7d == expiresAt（非严格大于），不算临期。
        Instant now = expiresAt.minus(7, ChronoUnit.DAYS);
        assertFalse(policy.isExpired(changedAt, now));
        assertFalse(policy.isExpiringSoon(changedAt, now));
    }

    @Test
    void withinGraceWindow_isExpiringSoonButNotExpired() {
        // 剩余 6 天（< 7 天）：临期但仍可登录。
        Instant now = expiresAt.minus(6, ChronoUnit.DAYS);
        assertFalse(policy.isExpired(changedAt, now));
        assertTrue(policy.isExpiringSoon(changedAt, now));
    }

    @Test
    void oneSecondBeforeExpiry_isExpiringSoonButNotExpired() {
        Instant now = expiresAt.minusSeconds(1);
        assertFalse(policy.isExpired(changedAt, now));
        assertTrue(policy.isExpiringSoon(changedAt, now));
    }

    @Test
    void exactlyAtExpiry_isExpired_andNotDoubleFlaggedExpiringSoon() {
        // 到期时刻：isBefore 为 false → 严格过期；过期不再重复标临期。
        assertTrue(policy.isExpired(changedAt, expiresAt));
        assertFalse(policy.isExpiringSoon(changedAt, expiresAt));
    }

    @Test
    void afterExpiry_isExpired() {
        Instant now = expiresAt.plus(1, ChronoUnit.DAYS);
        assertTrue(policy.isExpired(changedAt, now));
        assertFalse(policy.isExpiringSoon(changedAt, now));
    }

    @Test
    void nullChangedAt_isTreatedExpired_neverExpiringSoon() {
        Instant now = changedAt.plus(30, ChronoUnit.DAYS);
        assertTrue(policy.isExpired(null, now), "缺少起算点（历史脏数据）应按过期处理，强制走重置");
        assertFalse(policy.isExpiringSoon(null, now));
    }
}
