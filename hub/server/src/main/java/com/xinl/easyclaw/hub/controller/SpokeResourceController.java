package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.spoke.SpokeResourceAuthorizeCheck;
import com.xinl.easyclaw.hub.contract.spoke.SpokeResourceItem;
import com.xinl.easyclaw.hub.security.AppKeyContextHolder;
import com.xinl.easyclaw.hub.service.resource.SpokeResourceCatalog;
import com.xinl.easyclaw.hub.service.resource.SpokeResourceRegistry;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通用资源下发端点（/api/spoke/resources/**，appkey 认证，V30）：资源下放能力的统一管道。
 * 类型注册表路由——新增资源类型 = 新增 {@link SpokeResourceCatalog} 实现 bean，本端点零改动。
 * 现注册：db-connection（数据库连接目录）；ops-server 存量走 /api/spoke/ops-servers 旧端点，
 * 迁移后并入。过滤口径全类型一致：enabled + 归属组织 + 项目过滤 + 用户未过期授权。
 */
@RestController
public class SpokeResourceController {

    private final SpokeResourceRegistry registry;

    public SpokeResourceController(SpokeResourceRegistry registry) {
        this.registry = registry;
    }

    /** 下发指定类型的资源目录（仅启用 + 当前组织 + 项目过滤 + 当前用户未过期授权，保序）。 */
    @GetMapping("/api/spoke/resources/{type}")
    public List<SpokeResourceItem> resources(@PathVariable("type") String type,
                                             @RequestParam(value = "projectId", required = false) Long projectId) {
        SpokeResourceCatalog catalog = registry.require(type);
        return catalog.listEnabledForSpoke(AppKeyContextHolder.require(), projectId);
    }

    /** 活跃连接授权校验（spoke 定时批量调用）：serverKey → 是否仍有未过期授权；未知 key 一律 false。 */
    @PostMapping("/api/spoke/resources/{type}/authorize-check")
    public Map<String, Boolean> authorizeCheck(@PathVariable("type") String type,
                                               @Valid @RequestBody SpokeResourceAuthorizeCheck req) {
        SpokeResourceCatalog catalog = registry.require(type);
        return catalog.authorizeCheck(AppKeyContextHolder.require(), req.serverKeys());
    }
}
