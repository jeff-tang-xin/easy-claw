package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.featureflag.OrgFlagSettingDto;
import com.xinl.easyclaw.hub.contract.featureflag.SetFlagEnabledRequest;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.FeatureFlagService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织功能开关端点（组织侧只有启用开关，目录 CRUD 在 PlatformCatalogController）：
 * GET /api/orgs/{orgId}/flag-settings 全量目录 × 生效态（读=成员）；
 * PUT /api/orgs/{orgId}/flag-settings/{flagId} 幂等设置（写=owner/admin，service 层裁决）。
 */
@RestController
public class FeatureFlagController {

    private final FeatureFlagService flags;

    public FeatureFlagController(FeatureFlagService flags) {
        this.flags = flags;
    }

    @GetMapping("/api/orgs/{orgId}/flag-settings")
    public List<OrgFlagSettingDto> listSettings(@PathVariable Long orgId) {
        return flags.listSettings(CurrentUserHolder.requireUserId(), orgId);
    }

    @PutMapping("/api/orgs/{orgId}/flag-settings/{flagId}")
    public ResponseEntity<Void> setEnabled(@PathVariable Long orgId, @PathVariable Long flagId,
                                           @Valid @RequestBody SetFlagEnabledRequest req) {
        flags.setEnabled(CurrentUserHolder.requireUserId(), orgId, flagId, req.enabled());
        return ResponseEntity.noContent().build();
    }
}
