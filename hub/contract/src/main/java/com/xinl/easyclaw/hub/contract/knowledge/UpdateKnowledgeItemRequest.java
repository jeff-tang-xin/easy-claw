package com.xinl.easyclaw.hub.contract.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新知识条目请求（整体覆盖 topic/summary/content）。expectedVersion 为客户端编辑时基于的版本号
 * （设计稿中的 base_version 即此字段），与库内当前版本不一致时
 * 返回 409 VERSION_CONFLICT 且 details 携带最新快照，供客户端手动合并。
 */
public record UpdateKnowledgeItemRequest(
        @NotBlank @Size(max = 200) String topic,
        @Size(max = 500) String summary,
        @Size(max = 50000) String content,
        @NotNull Long expectedVersion) {
}
