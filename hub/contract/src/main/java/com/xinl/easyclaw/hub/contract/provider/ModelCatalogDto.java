package com.xinl.easyclaw.hub.contract.provider;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 模型目录视图：模型名全局唯一，creditCost 为每次请求消耗积分（1 位小数，未登记模型默认 1）。
 * provider 的 models 清单按名称引用目录模型。
 */
public record ModelCatalogDto(Long id, String modelName, BigDecimal creditCost, String remark, Instant createdAt) {
}
