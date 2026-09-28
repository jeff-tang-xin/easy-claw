package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.provider.AddProviderCreditsRequest;
import com.xinl.easyclaw.hub.contract.provider.CreateProviderGrantRequest;
import com.xinl.easyclaw.hub.contract.provider.ProviderCreditDto;
import com.xinl.easyclaw.hub.contract.provider.ProviderGrantDto;
import com.xinl.easyclaw.hub.contract.provider.UpdateGrantPlanRequest;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.ProviderGrantService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider 授权端点：按用户授权 provider（可选每日调用次数上限与有效期）。
 * 管理权限与 provider 一致：平台池仅 platformAdmin，组织 provider 由 owner/admin（service 层判定）。
 * V27 积分池：PATCH /{grantId}/credit-plan 更新周期发放计划；
 * POST /{grantId}/credits 手动发放临时积分；GET /{grantId}/credits 积分流水。
 */
@RestController
@RequestMapping("/api/providers/{providerId}/grants")
public class ProviderGrantController {

    private final ProviderGrantService grantService;

    public ProviderGrantController(ProviderGrantService grantService) {
        this.grantService = grantService;
    }

    @GetMapping
    public List<ProviderGrantDto> list(@PathVariable Long providerId) {
        return grantService.list(CurrentUserHolder.requireUserId(), providerId);
    }

    @PostMapping
    public ResponseEntity<ProviderGrantDto> create(@PathVariable Long providerId,
                                                   @Valid @RequestBody CreateProviderGrantRequest req) {
        return ResponseEntity.status(201)
                .body(grantService.create(CurrentUserHolder.requireUserId(), providerId, req));
    }

    @DeleteMapping("/{grantId}")
    public ResponseEntity<Void> delete(@PathVariable Long providerId, @PathVariable Long grantId) {
        grantService.delete(CurrentUserHolder.requireUserId(), providerId, grantId);
        return ResponseEntity.noContent().build();
    }

    /** 更新授权的周期积分发放计划（每日/每月/每年，NULL = 不发放该周期）。 */
    @PatchMapping("/{grantId}/credit-plan")
    public ProviderGrantDto updatePlan(@PathVariable Long providerId, @PathVariable Long grantId,
                                       @Valid @RequestBody UpdateGrantPlanRequest req) {
        return grantService.updatePlan(CurrentUserHolder.requireUserId(), providerId, grantId, req);
    }

    /** 手动发放临时积分（面额 + 有效期必填），与周期积分同池 FIFO 消耗。 */
    @PostMapping("/{grantId}/credits")
    public ResponseEntity<ProviderCreditDto> addCredits(@PathVariable Long providerId,
                                                        @PathVariable Long grantId,
                                                        @Valid @RequestBody AddProviderCreditsRequest req) {
        return ResponseEntity.status(201)
                .body(grantService.addTempCredits(CurrentUserHolder.requireUserId(), providerId, grantId, req));
    }

    /** 积分流水（含已过期/已耗尽行，按发放时间倒序）。 */
    @GetMapping("/{grantId}/credits")
    public List<ProviderCreditDto> listCredits(@PathVariable Long providerId, @PathVariable Long grantId) {
        return grantService.listCreditRows(CurrentUserHolder.requireUserId(), providerId, grantId);
    }
}
