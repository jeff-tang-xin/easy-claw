package com.xinl.easyclaw.memory.settings;

import io.agentscope.harness.agent.memory.MemoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 记忆设置服务：装配层与管理端点的统一读取入口。
 * <p>
 * 单用户运行时以固定 {@value #LOCAL_USER_ID} 为键；读不到记录时按实体默认值
 * 落库一行（getOrCreate），保证装配层永远拿到非 null、字段非 null 的设置。
 */
@Service
public class MemorySettingsService {

    private static final Logger log = LoggerFactory.getLogger(MemorySettingsService.class);

    /** 单用户运行时的固定用户键，与 RuntimeContext userId 现状一致。 */
    public static final String LOCAL_USER_ID = "local";

    private final MemorySettingsRepository repository;

    public MemorySettingsService(MemorySettingsRepository repository) {
        this.repository = repository;
    }

    /** 读取本地用户设置，不存在则按默认值创建。装配层热路径调用，结果单行、开销可忽略。 */
    @Transactional
    public MemorySettingsEntity getOrCreate() {
        return getOrCreate(LOCAL_USER_ID);
    }

    @Transactional
    public MemorySettingsEntity getOrCreate(String userId) {
        MemorySettingsEntity entity = repository.findByUserId(userId)
                .orElseGet(() -> {
                    MemorySettingsEntity created = MemorySettingsEntity.builder()
                            .userId(userId)
                            .build();
                    // 触发 @PrePersist 默认值填充
                    MemorySettingsEntity saved = repository.save(created);
                    log.info("记忆设置初始化：userId={}，采用默认（窗口 {} tokens / flush {} / 子 Agent 提取 {}）",
                            userId, saved.getContextWindowTokens(), saved.getFlushMode(),
                            saved.getSubagentFlushEnabled());
                    return saved;
                });
        // ddl-auto 只加列不写默认值，新增字段对存量行读回为 NULL——此处幂等补默认
        if (backfillDefaults(entity)) {
            entity = repository.save(entity);
        }
        return entity;
    }

    /** 新增字段 NULL 兜底（存量行兼容）；有填充返回 true。 */
    private boolean backfillDefaults(MemorySettingsEntity e) {
        boolean dirty = false;
        if (e.getFlushAsyncEnabled() == null) {
            e.setFlushAsyncEnabled(true);
            dirty = true;
        }
        if (e.getFlushWindowMessages() == null) {
            e.setFlushWindowMessages(10);
            dirty = true;
        }
        if (e.getFlushBackgroundMessages() == null) {
            e.setFlushBackgroundMessages(5);
            dirty = true;
        }
        return dirty;
    }

    /**
     * 把用户级记忆设置翻译为 vendored {@link MemoryConfig}（装配层在 build 时调用）。
     * <p>
     * flush 触发恒为 NEVER：每回合提取已由 web 侧 ScopedMemoryFlushMiddleware 接管
     * （全量上下文载荷 + 同步/异步开关 + minGap 节流），vendored MemoryFlushMiddleware
     * 注册但永不触发——仅绕开其「concatWith 拖住主流直至提取完成」的执行期缺陷；
     * 载荷本身仍是 vendored 原生的全量上下文（web 侧 ScopedMemoryFlushService 快照
     * state.getContext() 后复用同一个 MemoryFlushManager）。本配置仅保留 retention
     * 等账簿策略；flushMode/minGap 由 web 侧消费。
     */
    public MemoryConfig toMemoryConfig(MemorySettingsEntity settings) {
        return MemoryConfig.builder()
                .flushTrigger(MemoryConfig.FlushTrigger.never())
                .dailyFileRetentionDays(settings.getDailyRetentionDays())
                .build();
    }

    /**
     * 部分更新：仅覆盖非 null 字段；flushMode 做合法值校验，非法值抛 IllegalArgumentException
     * 由调用方翻译为 400。
     */
    @Transactional
    public MemorySettingsEntity update(String userId, MemorySettingsEntity patch) {
        MemorySettingsEntity entity = getOrCreate(userId);
        if (patch.getContextWindowTokens() != null) {
            entity.setContextWindowTokens(patch.getContextWindowTokens());
        }
        if (patch.getFlushMode() != null) {
            String mode = patch.getFlushMode().trim().toLowerCase();
            if (!mode.equals("always") && !mode.equals("throttled") && !mode.equals("never")) {
                throw new IllegalArgumentException(
                        "非法 flushMode：" + patch.getFlushMode() + "（合法值：always / throttled / never）");
            }
            entity.setFlushMode(mode);
        }
        if (patch.getFlushMinGapMinutes() != null) {
            entity.setFlushMinGapMinutes(patch.getFlushMinGapMinutes());
        }
        if (patch.getSubagentFlushEnabled() != null) {
            entity.setSubagentFlushEnabled(patch.getSubagentFlushEnabled());
        }
        if (patch.getMemoryMdMaxKb() != null) {
            entity.setMemoryMdMaxKb(patch.getMemoryMdMaxKb());
        }
        if (patch.getDailyRetentionDays() != null) {
            entity.setDailyRetentionDays(patch.getDailyRetentionDays());
        }
        if (patch.getFlushAsyncEnabled() != null) {
            entity.setFlushAsyncEnabled(patch.getFlushAsyncEnabled());
        }
        if (patch.getFlushWindowMessages() != null) {
            int w = patch.getFlushWindowMessages();
            if (w < 1 || w > 50) {
                throw new IllegalArgumentException(
                        "非法 flushWindowMessages：" + w + "（合法范围 1-50）");
            }
            entity.setFlushWindowMessages(w);
        }
        if (patch.getFlushBackgroundMessages() != null) {
            int b = patch.getFlushBackgroundMessages();
            if (b < 0 || b > 20) {
                throw new IllegalArgumentException(
                        "非法 flushBackgroundMessages：" + b + "（合法范围 0-20）");
            }
            entity.setFlushBackgroundMessages(b);
        }
        return repository.save(entity);
    }
}
