package com.xinl.easyclaw.hub.contract.spoke;

import java.util.List;

/**
 * 下发给 spoke 的数据库连接目录项（仅 enabled 且当前 appkey 用户有未过期授权，按 sort_order,id 保序）。
 * 与 {@link SpokeOpsServer} 同构；ops 存量链路不动，db 走通用资源下发机制（V30 resource_grants）。
 * projectIds 供 spoke 侧按工作区归属项目过滤（V31 N:N：绑定集合；空 = 未绑定任何项目，全部可见）；
 * password 为解密后的明文（仅服务端快照持有，下发浏览器的视图一律置 null）；
 * hasPassword 供前端预判「一键连接 vs 当次手输」。
 * readonlyHint：hub 录入时提示「只读账号」的勾选标记，随目录下发注入提示词（物理防线提醒）。
 * databaseName 可空（V33：MySQL/PG/SQLServer 可只连实例；Oracle 存 service_name 必填）。
 */
public record SpokeDbConnection(
        String serverKey,
        String name,
        String dbType,
        String host,
        Integer port,
        String databaseName,
        String username,
        String description,
        Boolean readonlyHint,
        List<Long> projectIds,
        String password,
        Boolean hasPassword) {
}
