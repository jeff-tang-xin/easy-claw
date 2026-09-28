package com.xinl.easyclaw.hub.contract.workspace;

import jakarta.validation.constraints.Size;

/**
 * 修改工作区请求：name 改展示名，status 用于归档（active|archived）；均可空（不传即不改）。
 */
public record UpdateWorkspaceRequest(
        @Size(max = 128) String name,
        @Size(max = 20) String status) {
}
