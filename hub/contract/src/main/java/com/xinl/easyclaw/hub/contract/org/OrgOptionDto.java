package com.xinl.easyclaw.hub.contract.org;

/**
 * 组织选项（添加用户/创建 provider 表单的下拉数据源）：仅平台管理员可拉取全量清单。
 */
public record OrgOptionDto(Long id, String name, String slug) {
}
