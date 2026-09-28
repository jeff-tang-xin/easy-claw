package com.xinl.easyclaw.config;

import java.util.List;

/**
 * hub 下发的 shell 命令白名单条目（cloud-config 配置下发用）。
 *
 * @param cmd         命令名（如 git、docker）
 * @param subcommands 允许的子命令清单；空 = 整命令放行
 */
public record SpokeShellCommandView(String cmd, List<String> subcommands) {
}
