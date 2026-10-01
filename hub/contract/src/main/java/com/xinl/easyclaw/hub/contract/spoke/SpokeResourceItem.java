package com.xinl.easyclaw.hub.contract.spoke;

import java.util.Map;

/**
 * 通用资源下发信封（GET /api/spoke/resources/{type}，V30）：资源下放能力的传输协议。
 * 类型特有字段进 attributes（如 db-connection 的 dbType/host/port/databaseName/username/
 * readonlyHint/projectId/hasPassword；ops-server 迁移后为 host/port/username/osType/...），
 * spoke 侧各消费方从信封解析自己的类型化视图。
 * password 为 hub 解密后的明文（仅服务端快照持有，供 spoke 直连；null = 未设置）——
 * 下发浏览器的视图一律不含密码。
 */
public record SpokeResourceItem(
        String serverKey,
        String name,
        String type,
        Map<String, String> attributes,
        String password) {
}
