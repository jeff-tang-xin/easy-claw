package com.xinl.easyclaw.hub.contract.provider;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/**
 * 更新授权积分发放计划：三个周期各自独立配置（NULL = 不发放该周期积分）。
 * 每日积分当天有效次日重发；每月/每年积分当期有效。修改后下一周期按新值发放，
 * 已发放的当期积分不回溯调整。三项全 NULL = 该授权不启用积分池（仅按 daily_limit 限流）。
 * 数值支持 1 位小数（服务端按 DOWN 舍弃多余位数，不进位）。
 */
public record UpdateGrantPlanRequest(
        @DecimalMin("0.1") @DecimalMax("1000000") @Digits(integer = 6, fraction = 1) BigDecimal dailyCredits,
        @DecimalMin("0.1") @DecimalMax("1000000") @Digits(integer = 6, fraction = 1) BigDecimal monthlyCredits,
        @DecimalMin("0.1") @DecimalMax("1000000") @Digits(integer = 6, fraction = 1) BigDecimal yearlyCredits) {
}
