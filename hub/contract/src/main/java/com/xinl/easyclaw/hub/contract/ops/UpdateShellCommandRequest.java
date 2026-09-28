package com.xinl.easyclaw.hub.contract.ops;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 更新 Shell 命令白名单项请求（仅 platformAdmin）：字段全可空 = 不传不改；cmd 创建后不可改
 * （服务端裁决，请求体不含该字段）；subcommands 传 null = 不改，传列表 = 整体替换。
 */
public record UpdateShellCommandRequest(
        List<String> subcommands,
        Integer sortOrder,
        Boolean enabled) {
}
