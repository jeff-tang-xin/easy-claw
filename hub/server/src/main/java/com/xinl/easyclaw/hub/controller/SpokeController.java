package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.spoke.SpokeBootstrapResponse;
import com.xinl.easyclaw.hub.contract.spoke.SpokeFlagInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeMenuNode;
import com.xinl.easyclaw.hub.contract.spoke.SpokeToolInfo;
import com.xinl.easyclaw.hub.security.AppKeyContextHolder;
import com.xinl.easyclaw.hub.service.SpokeService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * spoke 数据面端点（/api/spoke/**，appkey 认证，见 AppKeyAuthFilter）：
 * 来自子系统的请求统一入口，一切配置与权限信息基于 appkey 上下文框定。
 */
@RestController
public class SpokeController {

    private final SpokeService spokeService;

    public SpokeController(SpokeService spokeService) {
        this.spokeService = spokeService;
    }

    /** bootstrap：spoke 启动/刷新时拉取自身配置快照（身份、组织、可用模型面、服务权限）。 */
    @GetMapping("/api/spoke/bootstrap")
    public SpokeBootstrapResponse bootstrap() {
        return spokeService.bootstrap(AppKeyContextHolder.require());
    }

    /** 下发本组织的生效菜单树（平台目录 × 组织可见性，仅生效项；spoke 侧消费在后续阶段）。 */
    @GetMapping("/api/spoke/menus")
    public List<SpokeMenuNode> menus() {
        return spokeService.distributeMenu(AppKeyContextHolder.require());
    }

    /** 下发本组织的生效功能开关（仅平台 enabled 项，enabled=平台 AND 组织）。 */
    @GetMapping("/api/spoke/feature-flags")
    public List<SpokeFlagInfo> featureFlags() {
        return spokeService.distributeFlags(AppKeyContextHolder.require());
    }

    /** 下发本组织的生效工具（仅平台 enabled 项，enabled=平台 AND 组织）。 */
    @GetMapping("/api/spoke/tools")
    public List<SpokeToolInfo> tools() {
        return spokeService.distributeTools(AppKeyContextHolder.require());
    }
}
