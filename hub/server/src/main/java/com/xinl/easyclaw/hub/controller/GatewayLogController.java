package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.contract.gateway.GatewayLogDetailDto;
import com.xinl.easyclaw.hub.contract.gateway.GatewayLogPageResponse;
import com.xinl.easyclaw.hub.contract.gateway.GatewayUsageDto;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.GatewayLogService;
import com.xinl.easyclaw.hub.service.OrgService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关详单/用量查询端点：组织维度，仅 owner/admin 可见（口径与审计日志一致，设计 §7）。
 * member/guest 无权查看；详单含请求/响应正文，属敏感数据。
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/gateway-logs")
public class GatewayLogController {

    private final GatewayLogService gatewayLogService;
    private final OrgService orgService;

    public GatewayLogController(GatewayLogService gatewayLogService, OrgService orgService) {
        this.gatewayLogService = gatewayLogService;
        this.orgService = orgService;
    }

    @GetMapping
    public GatewayLogPageResponse list(@PathVariable Long orgId,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String model,
                                       @RequestParam(required = false) String keyPrefix,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        requireOwnerOrAdmin(orgId);
        return gatewayLogService.query(orgId, status, model, keyPrefix, page, size);
    }

    @GetMapping("/usage")
    public GatewayUsageDto usage(@PathVariable Long orgId, @RequestParam(required = false) Integer days) {
        requireOwnerOrAdmin(orgId);
        return gatewayLogService.usage(orgId, days);
    }

    @GetMapping("/{id}")
    public GatewayLogDetailDto detail(@PathVariable Long orgId, @PathVariable Long id) {
        requireOwnerOrAdmin(orgId);
        return gatewayLogService.detail(orgId, id);
    }

    private void requireOwnerOrAdmin(Long orgId) {
        String role = orgService.roleOf(orgId, CurrentUserHolder.requireUserId());
        if (!"owner".equals(role) && !"admin".equals(role)) {
            throw ApiException.forbidden("网关详单仅 owner/admin 可见");
        }
    }
}
