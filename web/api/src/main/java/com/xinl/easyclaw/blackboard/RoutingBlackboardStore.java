package com.xinl.easyclaw.blackboard;

import com.xinl.easyclaw.config.CloudProperties;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 黑板存储分流：cloud 模式（配置了 app-key）委托 hub，本地模式落工作区文件。
 * <p>
 * {@code @Primary} 使 AI 工具（{@code BlackboardTools}）与 REST controller 注入到同一分流点，
 * 两条链路行为一致。路由判据按<b>调用时</b>现取 {@link CloudProperties#getAppKey()}：
 * app-key 是部署态配置（不随快照拉取成败变化），配置了即视为 cloud 模式 ——
 * 此时 hub 失败由 {@link CloudBlackboardStore} 抛错/返回失败说明，
 * <b>绝不静默降级写本地</b>（否则两套数据会分叉）；未配置 app-key 时行为与纯本地完全一致。
 */
@Primary
@Component
public class RoutingBlackboardStore implements BlackboardStore {

    private final CloudProperties cloud;
    private final LocalBlackboardStore local;
    private final CloudBlackboardStore cloudStore;

    public RoutingBlackboardStore(CloudProperties cloud,
                                  LocalBlackboardStore local,
                                  CloudBlackboardStore cloudStore) {
        this.cloud = cloud;
        this.local = local;
        this.cloudStore = cloudStore;
    }

    @Override
    public String append(WorkspaceContext workspace, String key, String author, String type, String content) {
        if (cloudMode()) {
            return cloudStore.append(workspace, key, author, type, content);
        }
        return local.append(workspace, key, author, type, content);
    }

    @Override
    public List<BlackboardEntry> read(WorkspaceContext workspace, String key, int limit) {
        if (cloudMode()) {
            return cloudStore.read(workspace, key, limit);
        }
        return local.read(workspace, key, limit);
    }

    @Override
    public List<BlackboardBook> listBooks(WorkspaceContext workspace) {
        if (cloudMode()) {
            return cloudStore.listBooks(workspace);
        }
        return local.listBooks(workspace);
    }

    @Override
    public String archiveBook(WorkspaceContext workspace, String key) {
        if (cloudMode()) {
            return cloudStore.archiveBook(workspace, key);
        }
        return local.archiveBook(workspace, key);
    }

    /** cloud 模式判定：app-key 已配置即分流到 hub（部署态决定，与快照拉取成败无关） */
    private boolean cloudMode() {
        String appKey = cloud.getAppKey();
        return appKey != null && !appKey.isBlank();
    }
}
