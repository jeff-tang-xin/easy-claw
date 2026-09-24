package com.xinl.easyclaw.hub.contract.spoke;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * spoke 命令记录批量上报请求（POST /api/spoke/ops-command-logs，V25）：
 * spoke 本地执行运维命令后异步批量上报 hub 落 ops_command_logs，供 platformAdmin 审计查询。
 * 单批上限 200 条（服务端裁决）；executedAt 为 ISO-8601 字符串，解析失败服务端回退当前时间；
 * source 仅允许 ai | user。
 */
public record SpokeOpsCommandLogReport(@NotEmpty @Size(max = 200) List<Item> logs) {

    /**
     * 单条命令记录：serverKey/serverName/host 为 spoke 侧快照（hub 不回查目录，服务器改名不影响历史）。
     */
    public record Item(
            @NotBlank @Size(max = 64) String serverKey,
            @Size(max = 128) String serverName,
            @Size(max = 255) String host,
            String command,
            @NotBlank @Size(max = 16) String source,
            String executedAt) {
    }
}
