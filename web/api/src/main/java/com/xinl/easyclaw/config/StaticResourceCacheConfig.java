package com.xinl.easyclaw.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * 静态资源缓存策略
 * <p>
 * 前端产物（vite）文件名带内容 hash：assets/** 可以放心长缓存；
 * index.html 是资源入口（引用带 hash 的 assets），必须每次协商（no-cache）。
 * <p>
 * 不加这个策略时，浏览器对 index.html 走启发式缓存（基于 Last-Modified），
 * 发版后浏览器仍可能加载旧 index.html + 旧 assets —— 表现为「包已更新、页面还是旧版」，
 * 需要手动强刷（Ctrl+F5）才恢复。
 */
@Configuration
public class StaticResourceCacheConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 入口页：no-cache = 可缓存但每次必须协商（ETag/Last-Modified，未变返回 304）
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/index.html")
                .setCacheControl(CacheControl.noCache());

        // 带 hash 的构建产物：内容不变则文件名不变，长缓存安全
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)));
    }
}
