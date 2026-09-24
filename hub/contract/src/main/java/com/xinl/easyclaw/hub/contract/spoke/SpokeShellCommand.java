package com.xinl.easyclaw.hub.contract.spoke;

import java.util.List;

/** 下发给 spoke 的 Shell 命令白名单项（仅 enabled，按 sort_order,id 保序）；subcommands 空 = 整命令放行。 */
public record SpokeShellCommand(
        String cmd,
        List<String> subcommands) {
}
