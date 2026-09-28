package com.xinl.easyclaw.hub.contract.workspace;

import java.time.LocalDateTime;

/**
 * 工作区视图：project 面向 spoke 的扩展面（1:1 绑定 project）。
 * menuCount 为该工作区已配置的菜单项总数（含子项），供列表概览，不含菜单明细。
 */
public record WorkspaceDto(
        Long id,
        Long projectId,
        Long orgId,
        String name,
        String status,
        long menuCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
