package com.xinl.easyclaw.hub.contract.docs;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新文档内容请求。title/content 任一非空即生成新版本；expectedVersion 为客户端编辑时基于的版本号，
 * 与库内当前版本不一致时返回 409 且 details 携带最新文档快照，供客户端做手动合并。
 */
public record UpdateDocRequest(
        @Size(max = 200) String title,
        String content,
        @NotNull Long expectedVersion) {
}
