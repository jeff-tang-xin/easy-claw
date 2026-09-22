package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.tool.OrgToolSettingDto;
import com.xinl.easyclaw.hub.contract.tool.SetToolEnabledRequest;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.ToolCatalogService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织工具启用端点（组织侧只有启用开关，工具目录与平台总开关在 PlatformCatalogController）：
 * GET /api/orgs/{orgId}/tool-settings 全量目录 × 该组织生效态（读=成员）；
 * PUT /api/orgs/{orgId}/tool-settings/{toolId} 幂等设置（写=owner/admin，service 层裁决）。
 */
@RestController
public class ToolController {

    private final ToolCatalogService tools;

    public ToolController(ToolCatalogService tools) {
        this.tools = tools;
    }

    @GetMapping("/api/orgs/{orgId}/tool-settings")
    public List<OrgToolSettingDto> listSettings(@PathVariable Long orgId) {
        return tools.listSettings(CurrentUserHolder.requireUserId(), orgId);
    }

    @PutMapping("/api/orgs/{orgId}/tool-settings/{toolId}")
    public ResponseEntity<Void> setEnabled(@PathVariable Long orgId, @PathVariable Long toolId,
                                           @Valid @RequestBody SetToolEnabledRequest req) {
        tools.setEnabled(CurrentUserHolder.requireUserId(), orgId, toolId, req.enabled());
        return ResponseEntity.noContent().build();
    }
}
