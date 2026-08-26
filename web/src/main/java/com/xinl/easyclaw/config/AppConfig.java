package com.xinl.easyclaw.config;

import com.xinl.easyclaw.memory.config.MemoryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用全局配置
 * <p>
 * 启用 Spring 定时任务调度，用于记忆遗忘等后台任务
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MemoryProperties.class)
public class AppConfig {
}
