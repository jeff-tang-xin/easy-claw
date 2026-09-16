package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.provider.CreateProviderRequest;
import com.xinl.easyclaw.hub.contract.provider.ProviderDto;
import com.xinl.easyclaw.hub.contract.provider.UpdateProviderRequest;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.ProviderService;
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
 * LLM provider 端点：列表返回 平台共享池 + 当前用户所在组织 的 provider（platformAdmin 见全部）；
 * 含 keyHint 掩码，永不回明文。增改删：平台池仅 platformAdmin，组织 provider 由 owner/admin 管理（service 层判定）。
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ProviderService providerService;

    public ProviderController(ProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping
    public List<ProviderDto> list() {
        return providerService.listForUser(CurrentUserHolder.requireUserId());
    }

    @PostMapping
    public ResponseEntity<ProviderDto> create(@Valid @RequestBody CreateProviderRequest req) {
        return ResponseEntity.status(201).body(providerService.create(CurrentUserHolder.requireUserId(), req));
    }

    @PatchMapping("/{id}")
    public ProviderDto update(@PathVariable Long id, @Valid @RequestBody UpdateProviderRequest req) {
        return providerService.update(CurrentUserHolder.requireUserId(), id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        providerService.delete(CurrentUserHolder.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
