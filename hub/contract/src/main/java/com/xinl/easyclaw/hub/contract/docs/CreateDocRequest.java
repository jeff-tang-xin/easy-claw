package com.xinl.easyclaw.hub.contract.docs;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新建文档请求。docType 缺省 requirement；parentDocId 用于把 task 挂到同项目的需求下（可空）。
 */
public record CreateDocRequest(
        @NotNull Long projectId,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 20) String docType,
        String content,
        Long parentDocId) {
}
