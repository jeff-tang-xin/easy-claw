package com.xinl.easyclaw.hub.contract.user;

import java.util.List;
import java.util.Set;

/**
 * {@code GET /api/role-matrix} 响应：组织角色 → 权限码的只读快照
 * （直接取自服务端唯一权威 {@code Permissions}），供控制台「角色与权限」页渲染关联矩阵。
 */
public record RoleMatrixResponse(
        List<RolePerms> roles,
        Set<String> platformAdminPerms) {

    /** 单个组织角色与其持有的权限码（roles 顺序固定：owner/admin/member/guest）。 */
    public record RolePerms(String role, Set<String> permissions) {
    }
}
