package com.xinl.easyclaw.hub.contract.ops;

import java.util.List;

/**
 * 运维命令记录分页响应。page 从 0 开始，size 为每页条数（口径同 GatewayLogPageResponse）。
 */
public record OpsCommandLogPageResponse(List<OpsCommandLogDto> items, long total, int page, int size) {
}
