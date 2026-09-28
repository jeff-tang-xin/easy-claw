package com.xinl.easyclaw.hub.contract.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新建知识条目。topic 项目内唯一（仅对未删除条目）；summary/content 可空，content 上限 50000。
 */
public record CreateKnowledgeItemRequest(
        @NotNull Long projectId,
        @NotBlank @Size(max = 200) String topic,
        @Size(max = 500) String summary,
        @Size(max = 50000) String content) {
}
