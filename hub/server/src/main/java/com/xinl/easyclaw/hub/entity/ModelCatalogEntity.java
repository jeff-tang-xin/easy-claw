package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 模型目录实体（model_catalog 表）：平台级模型清单与积分比例（platformAdmin 维护）。
 * provider 的 models 清单按名称引用目录模型；网关扣积分时按请求模型名取 credit_cost，
 * 未登记的模型默认 1 分/次。modelName 全局唯一。
 * 公共字段 id/created_at/updated_at 见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "model_catalog")
public class ModelCatalogEntity extends BaseEntity {

    /** 模型名（全局唯一），如 gpt-4o、claude-sonnet-4-5。 */
    @Column(name = "model_name", nullable = false, unique = true, length = 255)
    private String modelName;

    /** 每次请求消耗积分（≥0.1，支持 1 位小数，存储时多余位数舍弃不进位）；未登记模型按 1 分计。 */
    @Column(name = "credit_cost", nullable = false, precision = 10, scale = 1)
    private BigDecimal creditCost = BigDecimal.ONE;

    @Column(length = 255)
    private String remark;
}
