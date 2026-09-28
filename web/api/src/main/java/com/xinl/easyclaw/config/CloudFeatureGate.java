package com.xinl.easyclaw.config;

import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * cloud 平台目录门面（spec §4.2）：把「hub 下发的生效态开关/工具目录」翻译成
 * spoke 本地的两个裁决问题，消费方（ChatWebSocketHandler / AgentService / AgentFactory）
 * 依赖本门面而非直连 {@link CloudBootstrapService}。
 * <p>
 * 缺省语义（fail-open，与 hub 目录「无行 = 默认启用」一致）：
 * <ul>
 *   <li>本地模式 / 尚无成功快照 → 附件放行、无禁用工具（行为与接入 hub 前完全一致）</li>
 *   <li>快照存在但缺 {@code allow_attachments} flag → 附件放行</li>
 * </ul>
 * 线程安全：读侧无锁（快照为 volatile 整体替换的不可变记录）。
 */
@Service
public class CloudFeatureGate {

    private final CloudBootstrapService bootstrap;

    public CloudFeatureGate(CloudBootstrapService bootstrap) {
        this.bootstrap = bootstrap;
    }

    /**
     * 附件与图片是否允许上传。cloud 快照中 {@code allow_attachments=false} 时为 false。
     */
    public boolean attachmentsAllowed() {
        CloudBootstrapService.CloudSnapshot s = bootstrap.snapshot();
        return s == null || s.flags().getOrDefault(CloudBootstrapService.FLAG_ALLOW_ATTACHMENTS, true);
    }

    /**
     * cloud 平台目录中生效态为「停用」的工具 key 集合（含框架工具）。
     * 本地模式 / 无快照返回空集（不过滤）。
     */
    public Set<String> disabledTools() {
        CloudBootstrapService.CloudSnapshot s = bootstrap.snapshot();
        return s == null ? Set.of() : s.disabledTools();
    }
}
