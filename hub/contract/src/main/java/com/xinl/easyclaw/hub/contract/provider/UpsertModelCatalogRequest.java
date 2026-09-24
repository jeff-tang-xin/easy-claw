package com.xinl.easyclaw.hub.contract.provider;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 模型目录创建/更新入参：modelName 全局唯一；creditCost ≥0.1、最多 1 位小数
 * （服务端按 DOWN 舍弃多余位数，不进位）。
 */
public record UpsertModelCatalogRequest(
        @NotBlank @Size(max = 255) String modelName,
        @NotNull @DecimalMin("0.1") @DecimalMax("1000000") @Digits(integer = 6, fraction = 1) BigDecimal creditCost,
        @Size(max = 255) String remark) {
}
