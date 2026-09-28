package com.xinl.easyclaw.hub.contract.ops;

import jakarta.validation.constraints.Size;

/**
 * 更新运维服务器分类标签请求（仅 platformAdmin）：字段全可空 = 不传不改；
 * name 重命名时同步 ops_servers.category 引用行（事务内），新名与其他标签冲突 → 409。
 */
public record UpdateOpsServerCategoryRequest(
        @Size(max = 64) String name,
        Integer sortOrder) {
}
