package com.xinl.easyclaw.config;

/**
 * hub 下发的运维服务器条目（cloud-config 配置下发用）。
 *
 * @param serverKey   服务器唯一键
 * @param name        显示名
 * @param host        主机地址
 * @param port        端口（缺省 22）
 * @param username    登录用户名
 * @param description 描述（可空）
 * @param projectId   归属项目 id（V18：供前端按工作区绑定项目过滤；<=0/0 = 不限定项目，对所有项目可见；旧 hub 未下发该字段时为 null）
 * @param password    登录密码明文（V19：hub 解密后随目录下发；仅服务端快照持有，供 connect 按 serverKey 取用；
 *                    下发浏览器的视图一律置 null——密码不过 spoke→浏览器链路）
 * @param hasPassword 是否配置了密码（V24：供前端预判「一键连接 vs 当次手输」，替代明文字段的 UI 判据）
 * @param osType      服务器操作系统类型（V24：如 Linux/Windows；旧 hub 未下发该字段时为 null，容错）
 */
public record SpokeOpsServerView(String serverKey, String name, String host, int port,
                                 String username, String description, Long projectId, String password,
                                 Boolean hasPassword, String osType) {
}
