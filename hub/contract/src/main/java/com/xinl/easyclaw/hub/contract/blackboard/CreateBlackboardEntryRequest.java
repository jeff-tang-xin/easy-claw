package com.xinl.easyclaw.hub.contract.blackboard;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 追加一条黑板报条目。content 为纯文本，上限 5000。
 */
public record CreateBlackboardEntryRequest(
        @NotNull Long projectId,
        @NotBlank @Size(max = 5000) String content) {
}