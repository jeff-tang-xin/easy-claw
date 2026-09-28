package com.xinl.easyclaw.hub.contract.spoke;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * spoke 活跃运维连接授权校验请求（POST /api/spoke/ops-servers/authorize-check）：
 * spoke 定时（10s）对已建立的 SSH 连接批量校验 hub 侧授权状态，撤销授权后主动断开连接。
 * 单批上限 100 个 serverKey（服务端裁决）。
 */
public record SpokeOpsServerAuthorizeCheck(@NotEmpty @Size(max = 100) List<String> serverKeys) {
}
