package com.xinl.easyclaw.hub.contract.ops;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增运维服务器请求（仅 platformAdmin）。serverKey 全局唯一，仅允许小写字母/数字/连字符/下划线
 * （spoke 侧稳定标识，创建后不可改）；host 必填；port 缺省 22；username/description 可空
 * （服务端归一化为空串）。password 可空：非空则加密落库（V19）随目录下发 spoke 供临时运维直连；
 * 留空 = 不设密码（spoke 仍走本地录入凭证）。明文仅存在于请求体，任何回显不含密码。
 */
public record CreateOpsServerRequest(
        @Pattern(regexp = "[a-z0-9_-]+", message = "serverKey 只能包含小写字母/数字/连字符/下划线")
        @Size(max = 64) String serverKey,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 255) String host,
        @Min(1) @Max(65535) Integer port,
        @Size(max = 64) String username,
        @Size(max = 255) String description,
        @Size(max = 64) String osType,
        @NotBlank @Size(max = 64) String category,
        Integer sortOrder,
        Boolean enabled,
        @NotNull Long orgId,
        @NotNull Long projectId,
        @Size(max = 255) String password) {
}
