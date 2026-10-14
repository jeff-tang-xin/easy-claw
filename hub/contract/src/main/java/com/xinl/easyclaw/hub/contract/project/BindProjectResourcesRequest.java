package com.xinl.easyclaw.hub.contract.project;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 项目资源绑定请求（V31 N:N）：整组覆盖该项目绑定的数据库连接与运维服务器。
 * 两个清单即该项目的最终绑定全集——未列出的既有绑定被解除；空清单 = 清空该类绑定。
 * 资源以 serverKey 定位（目录稳定标识）；必须存在且归属项目所在组织（跨组织绑定拒绝）。
 */
public record BindProjectResourcesRequest(
        @Size(max = 100) List<String> dbServerKeys,
        @Size(max = 100) List<String> opsServerKeys) {
}
