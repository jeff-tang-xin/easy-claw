package com.xinl.easyclaw.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 界面品牌配置属性
 * <p>
 * 对应 application.yml 中 {@code easyclaw.branding.*} 配置项，
 * 经 {@code GET /api/branding} 下发给前端（侧边栏 logo / 浏览器标签页 / favicon）。
 * 未配置时回落到与旧版硬编码一致的默认值；修改配置后重启后端生效。
 */
@Data
@ConfigurationProperties(prefix = "easyclaw.branding")
public class BrandingProperties {

    /** 应用名：侧边栏标题 + 浏览器标签页后缀 */
    private String name = "Easy-Claw";

    /** 侧边栏标题下方的小字 */
    private String subtitle = "AI 编程助手";

    /** 图标：emoji（如 🦞）或图片地址（/xxx.png 或 http(s):// 外链），同时用作浏览器 favicon */
    private String icon = "🦞";
}
