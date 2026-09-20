package com.xinl.easyclaw.hub.contract.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建工作区请求：projectId 指定要绑定的项目（须与请求者同组织、且该项目尚无绑定工作区——1:1）；
 * name 可空，留空则由服务端取项目名。
 */
public record CreateWorkspaceRequest(
        @NotNull Long projectId,
        @Size(max = 128) String name) {
}
