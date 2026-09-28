package com.xinl.easyclaw.hub.contract.spoke;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * spoke 工作区黑板归档请求（POST /api/spoke/blackboard/archive）：
 * V24 统一后 = 该 (projectId, key) 本内 source=workspace 的活跃条目全部转 status=archived
 * （平台条目不受影响）；本不存在 → 404。
 */
public record SpokeBlackboardArchiveRequest(
        @NotBlank @Size(max = 64) String workspaceId,
        @NotNull Long projectId,
        @NotBlank @Size(max = 192) String key) {
}
