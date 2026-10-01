package com.xinl.easyclaw.hub.contract.spoke;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * spoke 活跃数据库连接授权校验请求（POST /api/spoke/resources/db-connection/authorize-check）：
 * spoke 定时批量调用，serverKey → 是否仍有未过期授权；未知 serverKey 一律 false（撤销/删除后连接必须断开）。
 */
public record SpokeDbConnectionAuthorizeCheck(@NotEmpty @Size(max = 200) List<String> serverKeys) {
}
