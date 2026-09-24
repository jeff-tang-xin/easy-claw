package com.xinl.easyclaw.knowledge;

import com.xinl.easyclaw.config.CloudProperties;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库存储分流：cloud 模式（配置了 app-key）委托 hub，本地模式落工作区文件 wiki。
 * <p>
 * {@code @Primary} 使 AI 工具（{@code KnowledgeTools}）、REST controller 与
 * WorkspaceSeedService 注入到同一分流点，三条链路行为一致。
 * 路由判据按<b>调用时</b>现取 {@link CloudProperties#getAppKey()}：
 * app-key 是部署态配置（不随快照拉取成败变化），配置了即视为 cloud 模式 ——
 * 此时 hub 失败由 {@link CloudKnowledgeService} 抛错/返回失败说明，
 * <b>绝不静默降级写本地</b>（否则两套数据会分叉）；未配置 app-key 时行为与纯本地完全一致。
 */
@Primary
@Service
public class RoutingKnowledgeService implements KnowledgeService {

    private final CloudProperties cloud;
    private final LocalKnowledgeService local;
    private final CloudKnowledgeService cloudService;

    public RoutingKnowledgeService(CloudProperties cloud,
                                   LocalKnowledgeService local,
                                   CloudKnowledgeService cloudService) {
        this.cloud = cloud;
        this.local = local;
        this.cloudService = cloudService;
    }

    @Override
    public String write(String topic, String summary, String content, WorkspaceContext workspace) {
        if (cloudMode()) {
            return cloudService.write(topic, summary, content, workspace);
        }
        return local.write(topic, summary, content, workspace);
    }

    @Override
    public List<KnowledgeEntry> list(WorkspaceContext workspace) {
        if (cloudMode()) {
            return cloudService.list(workspace);
        }
        return local.list(workspace);
    }

    @Override
    public String read(String topic, WorkspaceContext workspace) {
        if (cloudMode()) {
            return cloudService.read(topic, workspace);
        }
        return local.read(topic, workspace);
    }

    @Override
    public boolean exists(String topic, WorkspaceContext workspace) {
        if (cloudMode()) {
            return cloudService.exists(topic, workspace);
        }
        return local.exists(topic, workspace);
    }

    @Override
    public List<KnowledgeSearchHit> search(String query, int limit, WorkspaceContext workspace) {
        if (cloudMode()) {
            return cloudService.search(query, limit, workspace);
        }
        return local.search(query, limit, workspace);
    }

    /** cloud 模式判定：app-key 已配置即分流到 hub（部署态决定，与快照拉取成败无关） */
    private boolean cloudMode() {
        String appKey = cloud.getAppKey();
        return appKey != null && !appKey.isBlank();
    }
}
