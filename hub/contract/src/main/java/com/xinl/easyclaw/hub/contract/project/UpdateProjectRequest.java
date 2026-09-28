package com.xinl.easyclaw.hub.contract.project;

import jakarta.validation.constraints.Size;

/**
 * 更新项目请求。字段为空表示不更新该字段；status 可用于归档（archived）。
 */
public record UpdateProjectRequest(
        @Size(max = 128) String name,
        @Size(max = 500) String description,
        @Size(max = 20) String visibility,
        @Size(max = 20) String status) {
}
