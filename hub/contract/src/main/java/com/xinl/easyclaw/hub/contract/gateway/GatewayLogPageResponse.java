package com.xinl.easyclaw.hub.contract.gateway;

import java.util.List;

/**
 * 网关详单分页响应。page 从 0 开始，size 为每页条数。
 */
public record GatewayLogPageResponse(List<GatewayLogListItemDto> items, long total, int page, int size) {
}
