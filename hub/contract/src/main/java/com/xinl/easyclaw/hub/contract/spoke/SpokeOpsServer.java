package com.xinl.easyclaw.hub.contract.spoke;

/**
 * 下发给 spoke 的运维服务器目录项（仅 enabled 且当前 appkey 用户有未过期授权，按 sort_order,id 保序）。
 * projectId 供 spoke 侧按工作区绑定项目过滤（V18：服务器归属组织+项目）。
 * password 为解密后的明文（V19：hub 存密文、下发时解密，供 spoke 临时运维直连）；null = 未设置。
 */
public record SpokeOpsServer(
        String serverKey,
        String name,
        String host,
        Integer port,
        String username,
        String description,
        String osType,
        Long projectId,
        String password) {
}
