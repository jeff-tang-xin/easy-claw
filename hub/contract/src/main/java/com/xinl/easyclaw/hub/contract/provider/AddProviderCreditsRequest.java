package com.xinl.easyclaw.hub.contract.provider;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 手动发放临时积分：面额与有效期必填（有效期须在未来），典型值 1 天 / 1 个月。
 * 面额支持 1 位小数（服务端按 DOWN 舍弃多余位数，不进位）。
 * 临时积分与周期积分同池消耗，FIFO 按过期时间先后扣减。
 */
public record AddProviderCreditsRequest(
        @NotNull @DecimalMin("0.1") @DecimalMax("1000000") @Digits(integer = 6, fraction = 1) BigDecimal credits,
        @NotNull Instant expiresAt) {
}
