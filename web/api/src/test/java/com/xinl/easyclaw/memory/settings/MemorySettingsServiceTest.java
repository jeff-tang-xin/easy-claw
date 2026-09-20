package com.xinl.easyclaw.memory.settings;

import io.agentscope.harness.agent.memory.MemoryConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MemorySettingsService} 单元测试：默认值落库、幂等读取、部分更新、
 * flushMode 校验与 vendored MemoryConfig 映射。
 * <p>
 * 装配层行为（子 Agent disableMemoryHooks / maxContextTokens 生效）属
 * WorkspaceAgentBuilder 集成面，不在本测试范围。
 */
@ExtendWith(MockitoExtension.class)
class MemorySettingsServiceTest {

    @Mock
    private MemorySettingsRepository repository;

    @InjectMocks
    private MemorySettingsService service;

    /** 首次读取：按实体默认值落库一行（48K 窗 / always 每回合全量提取 / 子 Agent 关提取）。 */
    @Test
    void 首次读取按默认值落库() {
        when(repository.findByUserId("local")).thenReturn(Optional.empty());
        when(repository.save(any(MemorySettingsEntity.class))).thenAnswer(inv -> {
            MemorySettingsEntity e = inv.getArgument(0);
            e.onCreate(); // 模拟 JPA @PrePersist
            return e;
        });

        MemorySettingsEntity settings = service.getOrCreate();

        assertEquals(48000, settings.getContextWindowTokens());
        assertEquals("always", settings.getFlushMode());
        assertEquals(30, settings.getFlushMinGapMinutes());
        assertEquals(false, settings.getSubagentFlushEnabled());
        assertEquals(64, settings.getMemoryMdMaxKb());
        assertEquals(90, settings.getDailyRetentionDays());
        verify(repository).save(any(MemorySettingsEntity.class));
    }

    /** 已有行时直接复用，不再落库。 */
    @Test
    void 重复读取复用已有行() {
        MemorySettingsEntity existing = MemorySettingsEntity.builder().userId("local").build();
        existing.onCreate();
        when(repository.findByUserId("local")).thenReturn(Optional.of(existing));

        MemorySettingsEntity settings = service.getOrCreate();

        assertEquals(48000, settings.getContextWindowTokens());
        verify(repository, never()).save(any());
    }

    /** 部分更新：仅覆盖非 null 字段，null 字段保持原值。 */
    @Test
    void 部分更新只覆盖非空字段() {
        MemorySettingsEntity existing = MemorySettingsEntity.builder().userId("local").build();
        existing.onCreate();
        when(repository.findByUserId("local")).thenReturn(Optional.of(existing));
        when(repository.save(any(MemorySettingsEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MemorySettingsEntity patch = new MemorySettingsEntity();
        patch.setContextWindowTokens(96000);
        patch.setSubagentFlushEnabled(true);

        MemorySettingsEntity saved = service.update("local", patch);

        assertEquals(96000, saved.getContextWindowTokens());
        assertEquals(true, saved.getSubagentFlushEnabled());
        assertEquals("always", saved.getFlushMode()); // 未触碰
        assertEquals(30, saved.getFlushMinGapMinutes()); // 未触碰
    }

    /** flushMode 非法值：抛 IllegalArgumentException（由 Controller 翻译为 400）。 */
    @Test
    void 非法flushMode拒绝() {
        MemorySettingsEntity existing = MemorySettingsEntity.builder().userId("local").build();
        existing.onCreate();
        when(repository.findByUserId("local")).thenReturn(Optional.of(existing));

        MemorySettingsEntity patch = new MemorySettingsEntity();
        patch.setFlushMode("sometimes");

        assertThrows(IllegalArgumentException.class, () -> service.update("local", patch));
        verify(repository, never()).save(any());
    }

    /** flushMode 大小写不敏感，入库统一小写。 */
    @Test
    void flushMode大小写归一() {
        MemorySettingsEntity existing = MemorySettingsEntity.builder().userId("local").build();
        existing.onCreate();
        when(repository.findByUserId("local")).thenReturn(Optional.of(existing));
        when(repository.save(any(MemorySettingsEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MemorySettingsEntity patch = new MemorySettingsEntity();
        patch.setFlushMode("ALWAYS");

        assertEquals("always", service.update("local", patch).getFlushMode());
    }

    /**
     * vendored 提取触发恒 NEVER：任何 flushMode 取值都不再让 vendored middleware 触发——
     * 每回合提取由 web 侧 ScopedMemoryFlushMiddleware 按「框定窗口 + 同步/异步开关」接管，
     * vendored 全量载荷 + concatWith 拖住主流的模式被整体架空。
     */
    @Test
    void vendored提取触发恒为never() {
        MemorySettingsEntity e = MemorySettingsEntity.builder().userId("local").build();
        e.onCreate();

        for (String mode : new String[]{"always", "throttled", "never", "bogus"}) {
            e.setFlushMode(mode);
            assertEquals(MemoryConfig.FlushMode.NEVER,
                    service.toMemoryConfig(e).flushTrigger().mode(), "flushMode=" + mode);
        }
        e.setFlushMode(null);
        assertEquals(MemoryConfig.FlushMode.NEVER,
                service.toMemoryConfig(e).flushTrigger().mode());
        // 账簿保留策略仍直传 vendored
        assertEquals(90, service.toMemoryConfig(e).dailyFileRetentionDays());
    }

    /** 存量行兼容：新增字段读回 NULL（ddl-auto 只加列）时幂等补默认并落库一次。 */
    @Test
    void 存量行新字段空值幂等补默认() {
        MemorySettingsEntity existing = MemorySettingsEntity.builder().userId("local").build();
        // 不调 onCreate——模拟 ddl-auto 加列后存量行新字段为 NULL
        when(repository.findByUserId("local")).thenReturn(Optional.of(existing));
        when(repository.save(any(MemorySettingsEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MemorySettingsEntity settings = service.getOrCreate();

        assertEquals(true, settings.getFlushAsyncEnabled());
        assertEquals(10, settings.getFlushWindowMessages());
        assertEquals(5, settings.getFlushBackgroundMessages());
        verify(repository).save(any(MemorySettingsEntity.class));
    }

    /** 提取窗口/背景越界：抛 IllegalArgumentException（由 Controller 翻译为 400）。 */
    @Test
    void 提取窗口与背景越界拒绝() {
        MemorySettingsEntity existing = MemorySettingsEntity.builder().userId("local").build();
        existing.onCreate();
        when(repository.findByUserId("local")).thenReturn(Optional.of(existing));

        MemorySettingsEntity patch = new MemorySettingsEntity();
        patch.setFlushWindowMessages(0);
        assertThrows(IllegalArgumentException.class, () -> service.update("local", patch));
        patch.setFlushWindowMessages(51);
        assertThrows(IllegalArgumentException.class, () -> service.update("local", patch));

        patch.setFlushWindowMessages(null);
        patch.setFlushBackgroundMessages(-1);
        assertThrows(IllegalArgumentException.class, () -> service.update("local", patch));
        patch.setFlushBackgroundMessages(21);
        assertThrows(IllegalArgumentException.class, () -> service.update("local", patch));
        verify(repository, never()).save(any());
    }

    /** 提取执行方式与窗口/背景：部分更新透传，未触碰字段保持原值。 */
    @Test
    void 提取执行方式与窗口部分更新() {
        MemorySettingsEntity existing = MemorySettingsEntity.builder().userId("local").build();
        existing.onCreate();
        when(repository.findByUserId("local")).thenReturn(Optional.of(existing));
        when(repository.save(any(MemorySettingsEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MemorySettingsEntity patch = new MemorySettingsEntity();
        patch.setFlushAsyncEnabled(false);
        patch.setFlushWindowMessages(20);
        patch.setFlushBackgroundMessages(3);

        MemorySettingsEntity saved = service.update("local", patch);

        assertEquals(false, saved.getFlushAsyncEnabled());
        assertEquals(20, saved.getFlushWindowMessages());
        assertEquals(3, saved.getFlushBackgroundMessages());
        assertEquals(30, saved.getFlushMinGapMinutes()); // 未触碰
    }

    /** 实体默认值填充：onCreate 不覆盖显式设置的值，并顺带刷新 updatedAt。 */
    @Test
    void 默认值不覆盖显式赋值() {
        MemorySettingsEntity e = MemorySettingsEntity.builder()
                .userId("u1")
                .contextWindowTokens(32000)
                .flushMode("never")
                .build();
        e.onCreate();

        assertEquals(32000, e.getContextWindowTokens());
        assertEquals("never", e.getFlushMode());
        assertEquals(30, e.getFlushMinGapMinutes()); // 未显式赋值 → 默认
        assertTrue(e.getUpdatedAt() != null); // onCreate 顺带落时间戳
    }

    /** dailyRetentionDays 直传 vendored 每日流水保留策略。 */
    @Test
    void 保留天数直传() {
        MemorySettingsEntity e = MemorySettingsEntity.builder().userId("local").build();
        e.onCreate();
        e.setDailyRetentionDays(30);

        assertEquals(30, service.toMemoryConfig(e).dailyFileRetentionDays());
    }

    /** 子 Agent 提取开关语义：默认 false（装配层据此 disableMemoryHooks）。 */
    @Test
    void 子Agent提取默认关闭() {
        MemorySettingsEntity e = MemorySettingsEntity.builder().userId("local").build();
        e.onCreate();

        assertTrue(Boolean.FALSE.equals(e.getSubagentFlushEnabled()));
    }
}
