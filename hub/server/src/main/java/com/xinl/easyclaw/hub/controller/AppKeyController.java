package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.appkey.AppKeyCreatedResponse;
import com.xinl.easyclaw.hub.contract.appkey.AppKeyDto;
import com.xinl.easyclaw.hub.contract.appkey.CloudRouteRequest;
import com.xinl.easyclaw.hub.contract.appkey.CreateAppKeyRequest;
import com.xinl.easyclaw.hub.contract.appkey.UpdateBindingsRequest;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.AppKeyService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织级 appkey 端点：列表/颁发/吊销/绑定配置，仅组织 owner/admin（service 层按 memberships 判定）。
 * 明文 key 仅 POST 创建响应返回一次。
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/appkeys")
public class AppKeyController {

    private final AppKeyService appKeyService;

    public AppKeyController(AppKeyService appKeyService) {
        this.appKeyService = appKeyService;
    }

    @GetMapping
    public List<AppKeyDto> list(@PathVariable Long orgId) {
        return appKeyService.list(CurrentUserHolder.requireUserId(), orgId);
    }

    @PostMapping
    public ResponseEntity<AppKeyCreatedResponse> create(@PathVariable Long orgId,
                                                        @Valid @RequestBody CreateAppKeyRequest req) {
        return ResponseEntity.status(201).body(appKeyService.create(CurrentUserHolder.requireUserId(), orgId, req));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable Long orgId, @PathVariable Long id) {
        appKeyService.revoke(CurrentUserHolder.requireUserId(), orgId, id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/bindings")
    public AppKeyDto updateBindings(@PathVariable Long orgId, @PathVariable Long id,
                                    @Valid @RequestBody UpdateBindingsRequest req) {
        return appKeyService.updateBindings(CurrentUserHolder.requireUserId(), orgId, id, req);
    }

    /** 配置逻辑模型别名 hub_cloud 的默认路由（provider + 真实模型）。 */
    @PutMapping("/{id}/cloud-route")
    public AppKeyDto updateCloudRoute(@PathVariable Long orgId, @PathVariable Long id,
                                      @Valid @RequestBody CloudRouteRequest req) {
        return appKeyService.updateCloudRoute(CurrentUserHolder.requireUserId(), orgId, id, req);
    }

    /** 清除 hub_cloud 默认路由（停用该 key 的云端别名），幂等。 */
    @DeleteMapping("/{id}/cloud-route")
    public AppKeyDto clearCloudRoute(@PathVariable Long orgId, @PathVariable Long id) {
        return appKeyService.clearCloudRouteConfig(CurrentUserHolder.requireUserId(), orgId, id);
    }
}
