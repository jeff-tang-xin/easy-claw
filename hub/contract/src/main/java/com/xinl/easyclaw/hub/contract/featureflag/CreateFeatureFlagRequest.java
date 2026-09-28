package com.xinl.easyclaw.hub.contract.featureflag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增平台功能开关请求（仅 platformAdmin）。flagKey 可空（空=由 label 派生），显式传入时仅允许
 * 小写字母/数字/连字符/下划线，且全局唯一、创建后不可改。description 可空（服务端归一化为空串）。
 */
public record CreateFeatureFlagRequest(
        @Pattern(regexp = "[a-z0-9_-]+", message = "flagKey 只能包含小写字母/数字/连字符/下划线")
        @Size(max = 64) String flagKey,
        @NotBlank @Size(max = 128) String label,
        @Size(max = 512) String description,
        Integer sortOrder,
        Boolean enabled) {
}
