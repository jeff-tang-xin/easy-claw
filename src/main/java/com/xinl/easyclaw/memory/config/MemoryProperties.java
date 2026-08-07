package com.xinl.easyclaw.memory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆系统配置属性
 * <p>
 * 对应 application.yml 中 {@code ai.memory.*} 配置项
 */
@Data
@ConfigurationProperties(prefix = "ai.memory")
public class MemoryProperties {

    /** 是否启用记忆提取 */
    private boolean extractionEnabled = true;

    /** 召回记忆数量上限 */
    private int recallLimit = 10;

    /** 遗忘阈值（天数），超过此天数且引用次数不足的记忆将被清理 */
    private int forgetDays = 30;

    /** 最小引用次数，低于此值的记忆在遗忘周期后会被清理 */
    private int minReferenceCount = 3;

    /** 召回评分权重 */
    private Weights weights = new Weights();

    @Data
    public static class Weights {
        /** 时间近因权重 */
        private double recency = 0.25;
        /** 内容相关性权重 */
        private double content = 0.30;
        /** 上下文匹配权重 */
        private double context = 0.15;
        /** 情感强度权重 */
        private double emotional = 0.10;
        /** 关联强度权重 */
        private double associative = 0.10;
        /** 洞察价值权重 */
        private double insight = 0.10;
    }
}
