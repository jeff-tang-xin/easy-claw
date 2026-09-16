package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.spoke.SpokeBootstrapResponse;
import com.xinl.easyclaw.hub.security.AppKeyContextHolder;
import com.xinl.easyclaw.hub.service.SpokeService;
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
}
