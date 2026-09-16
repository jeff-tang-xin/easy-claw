package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.contract.audit.AuditLogPageResponse;
import com.xinl.easyclaw.hub.service.OrgService;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.xinl.easyclaw.hub.service.AuditService;

/**
 * 审计日志查询端点：按组织维度分页查询（最新在前），仅 owner/admin 可见。
 * member/guest 无权查看；auth 模块的平台级事件（orgId 为空）不属于任何组织，不会出现在这里。
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/audit-logs")
public class AuditLogController {

    private final AuditService auditService;
    private final OrgService orgService;

    public AuditLogController(AuditService auditService, OrgService orgService) {
        this.auditService = auditService;
        this.orgService = orgService;
    }

    @GetMapping
    public AuditLogPageResponse list(@PathVariable Long orgId,
                                     @RequestParam(required = false) String module,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        Long userId = CurrentUserHolder.requireUserId();
        String role = orgService.roleOf(orgId, userId);
        if (!"owner".equals(role) && !"admin".equals(role)) {
            throw ApiException.forbidden("审计日志仅 owner/admin 可见");
        }
        return auditService.query(orgId, module, page, size);
    }
}
