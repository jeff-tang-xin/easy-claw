package com.xinl.easyclaw.memory.settings;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 记忆设置实体（用户级）
 * <p>
 * 承载 vendored 记忆账簿（MEMORY.md + memory/YYYY-MM-DD.md）的全部可调旋钮：
 * 上下文压缩窗、记忆提取策略、子 Agent 提取开关、账簿体积上限与保留天数。
 * 装配层 {@code WorkspaceAgentBuilder} 在构建 HarnessAgent 时读取并翻译为
 * vendored {@code MemoryConfig}/{@code maxContextTokens}。
 * <p>
 * 取值边界与触发语义均由本实体默认列定义；flushMode 合法值：
 * {@code always}（每次 call 后提取）、{@code throttled}（按 minGap 节流）、
 * {@code never}（关闭自动提取，仅保留手动 memory_save）。
 */
@Entity
@Table(name = "memory_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemorySettingsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", unique = true, nullable = false, length = 64)
    private String userId;

    /** workspace 记忆注入预算（tokens）：MEMORY.md 内容超出此预算时按 token 裁剪注入系统提示。
     *  默认 48000。⚠ 不触发对话压缩——压缩由 agentscope.agent.compaction-* 决定。 */
    @Column(name = "context_window_tokens")
    private Integer contextWindowTokens;

    /** 记忆提取模式：always / throttled / never。默认 always（每回合作一次全量上下文提取）。 */
    @Column(name = "flush_mode", length = 16)
    private String flushMode;

    /** throttled 模式下的最小提取间隔（分钟）。默认 30。 */
    @Column(name = "flush_min_gap_minutes")
    private Integer flushMinGapMinutes;

    /** 子 Agent 是否参与记忆提取。默认 false——子的沉淀走黑板归口主 Agent。 */
    @Column(name = "subagent_flush_enabled")
    private Boolean subagentFlushEnabled;

    /** MEMORY.md 体积上限（KB）。超限触发淘汰，防止提取载荷自增长恶性循环。默认 64。 */
    @Column(name = "memory_md_max_kb")
    private Integer memoryMdMaxKb;

    /** 每日流水（memory/YYYY-MM-DD.md）保留天数。默认 90。 */
    @Column(name = "daily_retention_days")
    private Integer dailyRetentionDays;

    /** 提取执行方式：true=异步（回合结束立即返回，提取后台跑）；false=同步（等待提取完成）。默认 true。 */
    @Column(name = "flush_async_enabled")
    private Boolean flushAsyncEnabled;

    /**
     * @deprecated 增量切片时代的「单次提取新消息窗口」旋钮。记忆提取已改回 vendored 原生
     * 全量上下文载荷（方案 A，2026-09-10），该值不再被任何提取逻辑消费；字段与 DB 列保留
     * 仅为兼容存量行（ddl-auto 只加列不删列），设置页已移除对应控件。
     */
    @Deprecated
    @Column(name = "flush_window_messages")
    private Integer flushWindowMessages;

    /**
     * @deprecated 增量切片时代的「窗口前背景消息条数」旋钮。全量上下文改造后不再被消费，
     * 保留理由同 {@link #flushWindowMessages}。
     */
    @Deprecated
    @Column(name = "flush_background_messages")
    private Integer flushBackgroundMessages;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        updatedAt = Instant.now();
        if (contextWindowTokens == null) {
            contextWindowTokens = 48000;
        }
        if (flushMode == null || flushMode.isBlank()) {
            flushMode = "always";
        }
        if (flushMinGapMinutes == null) {
            flushMinGapMinutes = 30;
        }
        if (subagentFlushEnabled == null) {
            subagentFlushEnabled = false;
        }
        if (memoryMdMaxKb == null) {
            memoryMdMaxKb = 64;
        }
        if (dailyRetentionDays == null) {
            dailyRetentionDays = 90;
        }
        if (flushAsyncEnabled == null) {
            flushAsyncEnabled = true;
        }
        if (flushWindowMessages == null) {
            flushWindowMessages = 10;
        }
        if (flushBackgroundMessages == null) {
            flushBackgroundMessages = 5;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
