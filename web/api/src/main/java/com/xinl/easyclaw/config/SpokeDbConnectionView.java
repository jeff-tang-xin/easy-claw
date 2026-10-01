package com.xinl.easyclaw.config;

/**
 * hub 下发的数据库连接视图（spoke 侧，V30）：从通用资源信封（SpokeResourceItem，
 * type=db-connection）解析而来。password 为 hub 解密后的明文——仅服务端快照持有，
 * connect 按 serverKey 取用；下发浏览器的视图 password 恒为 null，仅保留 hasPassword
 * 供前端预判「一键连接 vs 当次手输」（同 ops 口径）。
 *
 * @param readonlyHint hub 侧标记的只读提示（行为层提示，物理防线是 DB 只读账号）
 */
public record SpokeDbConnectionView(String serverKey, String name, String dbType,
                                    String host, int port, String databaseName,
                                    String username, String description,
                                    boolean readonlyHint, Long projectId,
                                    String password, boolean hasPassword) {
}
