package com.xinl.easyclaw.hub.contract.ops;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 更新运维服务器请求（仅 platformAdmin）：字段全可空 = 不传不改；serverKey 创建后不可改
 * （服务端裁决，请求体不含该字段）。password 语义：null = 保持原密码不变；非 null = 覆盖
 * （空串 = 清除密码，spoke 回退本地录入凭证）。
 */
public record UpdateOpsServerRequest(
        @Size(max = 128) String name,
        @Size(max = 255) String host,
        @Min(1) @Max(65535) Integer port,
        @Size(max = 64) String username,
        @Size(max = 255) String description,
        @Size(max = 64) String osType,
        @Size(max = 64) String category,
        Integer sortOrder,
        Boolean enabled,
        Long orgId,
        Long projectId,
        @Size(max = 255) String password) {
}
