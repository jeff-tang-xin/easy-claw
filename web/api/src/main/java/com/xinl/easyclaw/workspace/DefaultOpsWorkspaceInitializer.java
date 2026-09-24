package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.config.AppConstants;
import com.xinl.easyclaw.config.SystemHomePaths;
import com.xinl.easyclaw.workspace.entity.WorkspaceEntity;
import com.xinl.easyclaw.workspace.repository.WorkspaceRepository;
import com.xinl.easyclaw.workspace.repository.WorkspaceScenarioRepository;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.scenario.repository.ScenarioRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 默认运维工作区初始化器。
 * <p>
 * 系统内置唯一运维 workspace（{@link AppConstants#DEFAULT_OPS_WORKSPACE_ID}）：
 * <ul>
 *   <li>工作地址固定为 {@code ~/.easyClaw/ops}（{@link SystemHomePaths#opsWorkspaceRoot()}）；</li>
 *   <li>type=ops（创建后不可变，Agent 装配据此收缩为最小 toolkit）；</li>
 *   <li>默认绑定内置运维场景（标识名 {@code ops}）。</li>
 * </ul>
 * 每次启动幂等执行：目录缺失则创建、workspace 行缺失则落库、场景未绑定则补绑。
 * 该 workspace 不在前端工作区列表展示，前端运维入口以固定 id 直接访问；
 * 用户不能创建/删除运维工作区。
 */
@Component
@Order(20)
public class DefaultOpsWorkspaceInitializer {

    private static final Logger log = LoggerFactory.getLogger(DefaultOpsWorkspaceInitializer.class);

    /** 内置运维场景标识名（见 SystemDataSeeder，mode=ops） */
    private static final String OPS_SCENARIO_NAME = "ops";

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceScenarioRepository workspaceScenarioRepository;
    private final ScenarioRepository scenarioRepository;
    private final WorkspaceFileLayout fileLayout;

    public DefaultOpsWorkspaceInitializer(WorkspaceRepository workspaceRepository,
                                          WorkspaceScenarioRepository workspaceScenarioRepository,
                                          ScenarioRepository scenarioRepository,
                                          WorkspaceFileLayout fileLayout) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceScenarioRepository = workspaceScenarioRepository;
        this.scenarioRepository = scenarioRepository;
        this.fileLayout = fileLayout;
    }

    @PostConstruct
    public void init() {
        try {
            Path opsPath = SystemHomePaths.opsWorkspaceRoot();
            Files.createDirectories(opsPath);
            // 与普通工作区一致的磁盘结构（.easyClaw/agent/state 等），幂等不破坏用户数据
            fileLayout.initialize(opsPath, opsPath.resolve(".easyClaw"));
            upsertWorkspace(opsPath);
            bindOpsScenario();
            log.info("默认运维工作区已就绪: id={}, path={}",
                    AppConstants.DEFAULT_OPS_WORKSPACE_ID, opsPath);
        } catch (IOException e) {
            // 不阻断启动：运维功能此时不可用，下次启动会重试
            log.warn("默认运维工作区初始化失败（运维功能暂不可用）: {}", e.getMessage());
        }
    }

    /** 落库默认运维 workspace 行（已存在则只校正关键内置字段，不动 projectId 等用户态字段） */
    @Transactional
    public void upsertWorkspace(Path opsPath) {
        WorkspaceEntity entity = workspaceRepository
                .findById(AppConstants.DEFAULT_OPS_WORKSPACE_ID)
                .orElseGet(() -> WorkspaceEntity.builder()
                        .id(AppConstants.DEFAULT_OPS_WORKSPACE_ID)
                        .userId(AppConstants.DEFAULT_USER_ID)
                        .name("运维工作区")
                        .createdAt(Instant.now())
                        .build());
        entity.setPath(opsPath.toString());
        entity.setType("ops");
        entity.setStatus("active");
        entity.setLastAccessedAt(Instant.now());
        workspaceRepository.save(entity);
    }

    /** 绑定内置运维场景（已绑定则不重复写） */
    @Transactional
    public void bindOpsScenario() {
        if (workspaceScenarioRepository
                .findByWorkspaceId(AppConstants.DEFAULT_OPS_WORKSPACE_ID).isPresent()) {
            return;
        }
        ScenarioEntity opsScenario = scenarioRepository.findByName(OPS_SCENARIO_NAME).orElse(null);
        if (opsScenario == null || !Boolean.TRUE.equals(opsScenario.getActive())) {
            log.warn("内置运维场景不存在或已停用，默认运维工作区暂未绑定场景（下次启动重试）");
            return;
        }
        workspaceScenarioRepository.save(
                com.xinl.easyclaw.workspace.entity.WorkspaceScenarioEntity.builder()
                        .workspaceId(AppConstants.DEFAULT_OPS_WORKSPACE_ID)
                        .scenarioId(opsScenario.getId())
                        .build());
    }
}
