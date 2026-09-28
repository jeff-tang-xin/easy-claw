package com.xinl.easyclaw.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CloudFeatureGate 缺省语义（spec §4.2）：
 * 无快照（本地模式/未拉取成功）恒放行；快照缺 allow_attachments flag 亦放行；
 * 快照存在时按生效态裁决。disabledTools 无快照=空集，有快照=原样透出。
 */
class CloudFeatureGateTest {

    private static CloudBootstrapService.CloudSnapshot snapshot(Map<String, Boolean> flags, Set<String> disabled) {
        return new CloudBootstrapService.CloudSnapshot("Acme", "acme", List.of(), List.of(),
                flags, disabled, List.of(), List.of(), List.of(), Instant.now());
    }

    @Test
    @DisplayName("无快照（本地模式/未拉取成功）：附件放行、无禁用工具")
    void nullSnapshotDefaultsOpen() {
        CloudBootstrapService bootstrap = mock(CloudBootstrapService.class);
        when(bootstrap.snapshot()).thenReturn(null);
        CloudFeatureGate gate = new CloudFeatureGate(bootstrap);

        assertTrue(gate.attachmentsAllowed());
        assertTrue(gate.disabledTools().isEmpty());
    }

    @Test
    @DisplayName("快照缺 allow_attachments flag：附件放行（缺省 true）")
    void missingFlagDefaultsAllowed() {
        CloudBootstrapService bootstrap = mock(CloudBootstrapService.class);
        when(bootstrap.snapshot()).thenReturn(snapshot(Map.of("chat", true), Set.of()));
        CloudFeatureGate gate = new CloudFeatureGate(bootstrap);

        assertTrue(gate.attachmentsAllowed());
    }

    @Test
    @DisplayName("allow_attachments=false：拒绝附件")
    void flagFalseRejectsAttachments() {
        CloudBootstrapService bootstrap = mock(CloudBootstrapService.class);
        when(bootstrap.snapshot()).thenReturn(snapshot(Map.of("allow_attachments", false), Set.of()));
        CloudFeatureGate gate = new CloudFeatureGate(bootstrap);

        assertFalse(gate.attachmentsAllowed());
    }

    @Test
    @DisplayName("disabledTools 原样透出快照中的生效态禁用集")
    void disabledToolsPassThrough() {
        CloudBootstrapService bootstrap = mock(CloudBootstrapService.class);
        when(bootstrap.snapshot()).thenReturn(snapshot(Map.of(), Set.of("web_search", "run_python")));
        CloudFeatureGate gate = new CloudFeatureGate(bootstrap);

        assertEquals(Set.of("web_search", "run_python"), gate.disabledTools());
    }

    @Test
    @DisplayName("真实本地实例（未 refresh）：与无快照同语义，不依赖 Mockito")
    void realLocalInstanceDefaultsOpen() {
        // appKey 未配置 → 本地模式；构造器不触网，snapshot 保持 null
        CloudFeatureGate gate = new CloudFeatureGate(new CloudBootstrapService(new CloudProperties()));

        assertTrue(gate.attachmentsAllowed());
        assertTrue(gate.disabledTools().isEmpty());
    }
}
