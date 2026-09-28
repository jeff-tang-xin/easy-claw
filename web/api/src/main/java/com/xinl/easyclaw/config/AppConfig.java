package com.xinl.easyclaw.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用全局配置
 * <p>
 * 启用 Spring 定时任务调度（会话清理等后台任务）
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({BrandingProperties.class})
public class AppConfig {
}
