package com.xinl.easyclaw.hub.contract.ops;

/**
 * 运维服务器分类标签字典项视图（ops_server_categories 表，V28）：
 * hub 统一维护（platformAdmin CRUD），服务器目录 category 收口为受管标签。
 */
public record OpsServerCategoryDto(
        Long id,
        String name,
        Integer sortOrder) {
}
