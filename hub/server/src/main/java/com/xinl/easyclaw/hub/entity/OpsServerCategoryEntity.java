package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 运维服务器分类标签字典（ops_server_categories，V28）：hub 统一维护（platformAdmin CRUD）。
 * 服务器目录（ops_servers.category，V21）从自由文本收口为受管标签：创建/更新服务器时
 * category 必须是本表已有标签（服务端裁决）；标签重命名同步引用行；被引用时禁止删除。
 */
@Getter
@Setter
@Entity
@Table(name = "ops_server_categories",
        uniqueConstraints = @UniqueConstraint(name = "uk_ops_category_name", columnNames = "name"))
public class OpsServerCategoryEntity extends BaseEntity {

    @Column(nullable = false, length = 64)
    private String name = "";

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}
