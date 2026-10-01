package com.xinl.easyclaw.hub.contract.spoke;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * spoke 数据库查询审计批量上报请求（POST /api/spoke/db-query-logs，V30）：
 * spoke 执行 AI 查询后异步批量上报 hub 落 db_query_logs，供 platformAdmin 审计查询。
 * 单批上限 200 条（服务端裁决）；executedAt 为 ISO-8601 字符串，解析失败服务端回退当前时间；
 * source 仅允许 ai | user。
 */
public record SpokeDbQueryLogReport(@NotEmpty @Size(max = 200) List<Item> logs) {

    /**
     * 单条查询记录：serverKey/serverName/dbType/host/databaseName 为 spoke 侧快照
     * （hub 不回查目录，连接后续改名不影响历史行）。
     */
    public record Item(
            @NotBlank @Size(max = 64) String serverKey,
            @Size(max = 128) String serverName,
            @Size(max = 32) String dbType,
            @Size(max = 255) String host,
            @Size(max = 128) String databaseName,
            String sqlText,
            @NotBlank @Size(max = 16) String source,
            String executedAt) {
    }
}
