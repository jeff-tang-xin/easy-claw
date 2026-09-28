package com.xinl.easyclaw.hub.contract.spoke;

import java.util.List;

/**
 * 下发给 spoke 的工作区视图（GET /api/spoke/workspaces）：工作区身份 + 其公共菜单树。
 * 菜单与组织无关，同一工作区的菜单对所有 spoke 通用；spoke 据 menu 渲染、据 permissions 框定服务。
 */
public record SpokeWorkspaceInfo(
        Long id,
        Long projectId,
        Long orgId,
        String name,
        List<SpokeMenuNode> menu) {
}
