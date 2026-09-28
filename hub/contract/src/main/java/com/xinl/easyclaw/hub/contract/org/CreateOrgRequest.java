package com.xinl.easyclaw.hub.contract.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建组织请求。slug 可空：留空由服务端从名称派生并保证唯一；显式传入需符合 slug 规则（小写字母/数字/连字符）。
 */
public record CreateOrgRequest(
        @NotBlank @Size(max = 128) String name,
        @Pattern(regexp = "[a-z0-9-]+", message = "slug 只能包含小写字母/数字/连字符") @Size(max = 64) String slug) {
}
