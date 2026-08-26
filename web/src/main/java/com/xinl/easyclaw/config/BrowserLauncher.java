package com.xinl.easyclaw.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

import java.awt.*;
import java.net.URI;

/**
 * 后端启动就绪后自动打开浏览器。
 * <p>
 * 不硬编码端口——监听 {@link WebServerInitializedEvent}，从事件里拿到
 * 实际绑定的端口（支持 server.port=0 随机分配、用户自定义端口等）。
 * <p>
 * 用 {@link Desktop#browse(URI)} 跨平台启动默认浏览器（Windows / macOS / Linux）。
 * 可通过配置 easyclaw.open-browser=false 关闭此行为。
 */
@Slf4j
@Configuration
public class BrowserLauncher implements ApplicationListener<WebServerInitializedEvent> {

    @Value("${easyclaw.open-browser:true}")
    private boolean openBrowser;

    @Value("${easyclaw.browser-host:localhost}")
    private String host;

    @Value("${easyclaw.browser-delay-ms:800}")
    private long delayMs;

    private boolean fired = false;

    @PostConstruct
    void init() {
        log.info("[Browser] 配置: openBrowser={}, host={}", openBrowser, host);
        if (!openBrowser) {
            log.info("[Browser] 已禁用 (easyclaw.open-browser=false)。");
        }
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (fired) return;
        fired = true;

        int port = event.getWebServer().getPort();
        String url = "http://" + host + ":" + port + "/";
        log.info("=============================================");
        log.info("  Easy Claw 启动就绪!");
        log.info("  访问地址: {}", url);
        log.info("  数据目录: {}", System.getProperty("user.home") + "/.easyClaw/");
        log.info("=============================================");

        if (!openBrowser) {
            log.info("[Browser] 自动打开浏览器已禁用 (easyclaw.open-browser=false)。");
            return;
        }

        // 在 WebServer 就绪时才检查 Desktop（@PostConstruct 时机可能过早在某些 JDK 下）
        boolean headless = java.awt.GraphicsEnvironment.isHeadless();
        boolean desktopSupported = Desktop.isDesktopSupported();
        boolean browseSupported = desktopSupported && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);

        log.info("[Browser] 环境检测: headless={}, desktopSupported={}, browseSupported={}",
                headless, desktopSupported, browseSupported);

        if (headless) {
            log.info("[Browser] headless 环境，跳过自动打开浏览器。");
            log.info("[Browser] 请手动访问: {}", url);
            return;
        }
        if (!desktopSupported) {
            log.info("[Browser] java.awt.Desktop 不可用，跳过自动打开浏览器。");
            log.info("[Browser] 请手动访问: {}", url);
            return;
        }
        if (!browseSupported) {
            log.info("[Browser] 当前 Desktop 不支持 BROWSE 操作，跳过自动打开浏览器。");
            log.info("[Browser] 请手动访问: {}", url);
            return;
        }

        // 延迟一点，让前端资源先就绪，再开浏览器
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                Desktop.getDesktop().browse(new URI(url));
                log.info("[Browser] 已打开浏览器: {}", url);
            } catch (Exception e) {
                log.warn("[Browser] 自动打开浏览器失败（不影响服务运行）: {}", e.getMessage());
                log.info("[Browser] 请手动访问: {}", url);
            }
        }, "EasyClaw-BrowserLauncher").start();
    }
}
