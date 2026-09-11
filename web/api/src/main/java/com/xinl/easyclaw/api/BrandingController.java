package com.xinl.easyclaw.api;

import com.xinl.easyclaw.config.BrandingProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 界面品牌定制查询接口
 * <p>
 * 前端启动时拉取一次，驱动侧边栏 logo、浏览器标签页标题与 favicon；
 * 本地单用户工具，与 {@code /api/system} 一样不做会话校验。
 */
@RestController
public class BrandingController {

    private final BrandingProperties props;

    public BrandingController(BrandingProperties props) {
        this.props = props;
    }

    @GetMapping("/api/branding")
    public BrandingResponse branding() {
        return new BrandingResponse(props.getName(), props.getSubtitle(), props.getIcon());
    }

    /** 独立 DTO 而非直接序列化 Properties：后续扩展字段时不会意外外泄配置 */
    public record BrandingResponse(String name, String subtitle, String icon) {
    }
}
