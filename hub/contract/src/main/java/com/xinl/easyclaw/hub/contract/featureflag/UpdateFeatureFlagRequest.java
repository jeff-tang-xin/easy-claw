package com.xinl.easyclaw.hub.contract.featureflag;

import jakarta.validation.constraints.Size;

/**
 * 修改平台功能开关请求（仅 platformAdmin）：字段均可空（不传即不改）；flagKey 创建后不可改（服务端裁决）。
 */
public record UpdateFeatureFlagRequest(
        @Size(max = 128) String label,
        @Size(max = 512) String description,
        Integer sortOrder,
        Boolean enabled) {
}
