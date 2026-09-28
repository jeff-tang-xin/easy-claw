package com.xinl.easyclaw.hub.contract.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建项目请求。orgId 指定所属组织；visibility 缺省 team。
 * slug 可空：留空由服务端从名称派生并保证组织内唯一；显式传入需符合 slug 规则。
 */
public record CreateProjectRequest(
        @NotNull Long orgId,
        @Pattern(regexp = "[a-z0-9-]+", message = "slug 只能包含小写字母/数字/连字符") @Size(max = 64) String slug,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 500) String description,
        @Size(max = 20) String visibility) {
}
