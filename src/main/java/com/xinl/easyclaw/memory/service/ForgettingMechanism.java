package com.xinl.easyclaw.memory.service;

import com.xinl.easyclaw.memory.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 记忆遗忘机制
 * <p>
 * 定时扫描并清理过期记忆，策略：
 * 1. 超过 forgetDays 天未访问
 * 2. 引用次数低于 minReferenceCount
 */
@Service
public class ForgettingMechanism {

    private static final Logger log = LoggerFactory.getLogger(ForgettingMechanism.class);

    private final MemoryService memoryService;
    private final MemoryProperties properties;

    public ForgettingMechanism(MemoryService memoryService, MemoryProperties properties) {
        this.memoryService = memoryService;
        this.properties = properties;
    }

    /**
     * 每日凌晨 3 点执行遗忘清理
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledForget() {
        log.info("开始执行定时记忆遗忘清理...");
        // 使用默认用户 ID "system" 执行全量清理
        // 实际生产中应遍历所有活跃用户
        int cleaned = memoryService.forget("system");
        log.info("记忆遗忘清理完成，共清理 {} 条过期记忆", cleaned);
    }

    /**
     * 手动触发指定用户的遗忘
     */
    public int triggerForget(String userId) {
        log.info("手动触发记忆遗忘: userId={}", userId);
        return memoryService.forget(userId);
    }

    /**
     * 获取遗忘统计信息
     */
    public String getForgetStats(String userId) {
        long total = memoryService.count(userId);
        long threshold = ChronoUnit.DAYS.between(
                Instant.now().minus(properties.getForgetDays(), ChronoUnit.DAYS),
                Instant.now()
        );
        return String.format("用户 %s: 总记忆数=%d, 遗忘阈值=%d天, 最小引用次数=%d",
                userId, total, properties.getForgetDays(), properties.getMinReferenceCount());
    }
}
