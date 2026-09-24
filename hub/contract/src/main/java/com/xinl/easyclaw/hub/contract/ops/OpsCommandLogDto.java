package com.xinl.easyclaw.hub.contract.ops;

import java.time.Instant;

/**
 * 运维服务器命令记录视图（ops_command_logs 表，V25）：spoke 执行命令后上报，platformAdmin 按服务器查询。
 * serverKey/serverName/host 为上报时快照（服务器后续改名不回溯历史行）；operator 为 spoke 侧操作者
 * （appkey 创建人 userId）；source 区分来源：ai（智能体执行）| user（用户手动执行）。
 */
public record OpsCommandLogDto(
        Long id,
        String serverKey,
        String serverName,
        String host,
        String command,
        String source,
        String operator,
        Instant executedAt,
        Instant createdAt) {
}
