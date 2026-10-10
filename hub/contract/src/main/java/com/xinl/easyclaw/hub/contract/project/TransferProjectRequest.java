package com.xinl.easyclaw.hub.contract.project;

import jakarta.validation.constraints.NotNull;

/**
 * 迁移项目到目标组织请求。
 * <p>
 * 仅源组织 admin 可发起；目标组织可为任意组织（不要求发起人是目标组织成员）。
 * 迁移后项目 org_id 改为目标组织，owner_user_id 改为执行迁移的 admin，
 * 挂 project_id 的资源（workspaces / db_connections / ops_servers）org_id 冗余字段一并同步。
 */
public record TransferProjectRequest(
        @NotNull(message = "目标组织不能为空") Long targetOrgId) {
}