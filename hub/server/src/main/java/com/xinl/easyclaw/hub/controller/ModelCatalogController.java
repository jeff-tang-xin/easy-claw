package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.provider.ModelCatalogDto;
import com.xinl.easyclaw.hub.contract.provider.UpsertModelCatalogRequest;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.ModelCatalogService;
import com.xinl.easyclaw.hub.service.PlatformAdminGuard;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型目录端点（/api/model-catalog）：平台级模型清单与积分比例。
 * 读对所有登录用户开放（积分页展示模型比例）；写仅 platformAdmin。
 */
@RestController
@RequestMapping("/api/model-catalog")
public class ModelCatalogController {

    private final ModelCatalogService service;
    private final PlatformAdminGuard platformAdminGuard;

    public ModelCatalogController(ModelCatalogService service, PlatformAdminGuard platformAdminGuard) {
        this.service = service;
        this.platformAdminGuard = platformAdminGuard;
    }

    @GetMapping
    public List<ModelCatalogDto> list() {
        CurrentUserHolder.requireUserId();
        return service.list();
    }

    @PostMapping
    public ModelCatalogDto create(@Valid @RequestBody UpsertModelCatalogRequest req) {
        Long actorId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(actorId);
        return service.create(actorId, req);
    }

    @PatchMapping("/{id}")
    public ModelCatalogDto update(@PathVariable Long id, @Valid @RequestBody UpsertModelCatalogRequest req) {
        Long actorId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(actorId);
        return service.update(actorId, id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        Long actorId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(actorId);
        service.delete(actorId, id);
    }
}
