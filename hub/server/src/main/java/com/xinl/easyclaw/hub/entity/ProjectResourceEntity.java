package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 项目-资源绑定（project_resources，V31）：db_connections / ops_servers 与 projects 的 N:N 归属。
 * <p>
 * 取代资源表上的单值 project_id（1:N 与实际不符——共享订单库、共用跳板机天然跨项目）。
 * 绑定操作收口在项目目录页（ProjectService.bindResources，整组覆盖）；资源添加页不再选项目。
 * resource_type 取 {@code ProjectResourceService.TYPE_DB} / {@code TYPE_OPS}；
 * 无外键（全库口径），resource_id 指向各类型目录表 id，一致性由应用层保证。
 */
@Getter
@Setter
@Entity
@Table(name = "project_resources",
        uniqueConstraints = @UniqueConstraint(name = "uk_project_resource",
                columnNames = {"project_id", "resource_type", "resource_id"}))
public class ProjectResourceEntity extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** db | ops（见 ProjectResourceService 类型常量）。 */
    @Column(name = "resource_type", nullable = false, length = 16)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;
}
