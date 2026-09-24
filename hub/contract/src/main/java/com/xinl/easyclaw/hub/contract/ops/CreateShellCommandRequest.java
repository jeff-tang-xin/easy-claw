package com.xinl.easyclaw.hub.contract.ops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 新增 Shell 命令白名单项请求（仅 platformAdmin）。cmd 全局唯一，仅允许小写字母/数字/点/连字符/下划线
 * （创建后不可改）；subcommands 可空（空 = 整命令放行），接口层收列表、库内存逗号分隔串。
 */
public record CreateShellCommandRequest(
        @Pattern(regexp = "[a-z0-9._-]+", message = "cmd 只能包含小写字母/数字/点/连字符/下划线")
        @Size(max = 64) String cmd,
        List<String> subcommands,
        Integer sortOrder,
        Boolean enabled) {
}
