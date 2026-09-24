package com.xinl.easyclaw.hub.contract.ops;

import java.util.List;

/**
 * Shell 命令白名单项视图（shell_commands 表）：hub 统一维护（platformAdmin CRUD），spoke 只读消费。
 * cmd 全局唯一、创建后不可改；subcommands 为子命令白名单（空列表 = 整命令放行）。
 */
public record ShellCommandDto(
        Long id,
        String cmd,
        List<String> subcommands,
        Integer sortOrder,
        Boolean enabled) {
}
