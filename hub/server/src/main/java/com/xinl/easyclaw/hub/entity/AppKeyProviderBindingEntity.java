package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * appkey↔provider 绑定实体（app_key_providers 表）：model_name 空串 = 该 provider 全部模型。
 * 全库无外键：app_key_id/provider_id 的引用一致性由应用层保证。公共字段见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "app_key_providers",
        uniqueConstraints = @UniqueConstraint(name = "uk_akp", columnNames = {"app_key_id", "provider_id", "model_name"}))
public class AppKeyProviderBindingEntity extends BaseEntity {

    @Column(name = "app_key_id", nullable = false)
    private Long appKeyId;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    /** 空串 = 该 provider 全部模型；非空必须在 provider 声明的 models 清单内（应用层校验）。 */
    @Column(name = "model_name", nullable = false, length = 64)
    private String modelName = "";
}
