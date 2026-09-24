package com.xinl.easyclaw.hub.contract.spoke;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * spoke 工作区黑板追加请求（POST /api/spoke/blackboard/entries）：
 * V24 统一落平台黑板 blackboard_entries（按 projectId 归属，跨 source 共享）；
 * 归档本（key 含 .archived-）拒绝追加。projectId 必填：工作区须绑定 hub 项目后才能同步。
 */
public record SpokeBlackboardAppendRequest(
        @NotBlank @Size(max = 64) String workspaceId,
        @NotNull Long projectId,
        @NotBlank @Size(max = 192) String key,
        @Size(max = 64) String author,
        @Size(max = 32) String type,
        String content) {
}
