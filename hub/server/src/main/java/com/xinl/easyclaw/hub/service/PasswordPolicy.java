package com.xinl.easyclaw.hub.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * 密码有效期策略（集中口径，便于调整与单测）：
 * <ul>
 *   <li>有效期 {@value #VALIDITY_DAYS} 天（2 个月）：从密码设置/上次修改时刻起算，到期后禁止登录，须管理员重置；</li>
 *   <li>到期前 {@value #GRACE_DAYS} 天进入临期窗口：仍可登录，控制台显著提示尽快改密（改密即重新起算）。</li>
 * </ul>
 * 纯时间计算、无副作用，所有方法均以「当前时刻」为参数便于在测试中固定时钟。
 */
@Component
public class PasswordPolicy {

    /** 密码有效期：60 天（2 个月）。 */
    public static final int VALIDITY_DAYS = 60;
    /** 临期提醒窗口：到期前 7 天。 */
    public static final int GRACE_DAYS = 7;

    /** 密码到期时刻 = 起算点 + 有效期。 */
    public Instant expiresAt(Instant passwordChangedAt) {
        return passwordChangedAt.plus(VALIDITY_DAYS, ChronoUnit.DAYS);
    }

    /** 是否已严格过期（到期时刻 &le; now），过期禁止登录。 */
    public boolean isExpired(Instant passwordChangedAt, Instant now) {
        return passwordChangedAt == null || !now.isBefore(expiresAt(passwordChangedAt));
    }

    /** 是否进入临期窗口（未过期，但距到期不足 GRACE_DAYS 天）。 */
    public boolean isExpiringSoon(Instant passwordChangedAt, Instant now) {
        if (passwordChangedAt == null) {
            return false;
        }
        Instant expiresAt = expiresAt(passwordChangedAt);
        if (!now.isBefore(expiresAt)) {
            return false; // 已过期归 expired，不重复标临期
        }
        return now.plus(GRACE_DAYS, ChronoUnit.DAYS).isAfter(expiresAt);
    }
}
