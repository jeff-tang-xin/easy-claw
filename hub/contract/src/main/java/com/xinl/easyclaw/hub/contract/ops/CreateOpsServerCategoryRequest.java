package com.xinl.easyclaw.hub.contract.ops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增运维服务器分类标签请求（仅 platformAdmin）：name 全局唯一（409）、创建后可重命名
 * （重命名同步 ops_servers.category 引用行）。
 */
public record CreateOpsServerCategoryRequest(
        @NotBlank @Size(max = 64) String name,
        Integer sortOrder) {
}
