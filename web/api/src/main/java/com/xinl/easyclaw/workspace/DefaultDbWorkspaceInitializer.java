package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.config.AppConstants;
import com.xinl.easyclaw.config.SystemHomePaths;
import com.xinl.easyclaw.workspace.entity.WorkspaceEntity;
import com.xinl.easyclaw.workspace.repository.WorkspaceRepository;
import com.xinl.easyclaw.workspace.repository.WorkspaceScenarioRepository;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.scenario.repository.ScenarioRepository;
import com.xinl.easyclaw.base.BuiltinDbIds;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 默认数据库工作区初始化器（V30，照 {@link DefaultOpsWorkspaceInitializer} 同构）。
 * <p>
 * 系统内置唯一数据库 workspace（{@link AppConstants#DEFAULT_DB_WORKSPACE_ID}）：
 * <ul>
 *   <li>工作地址固定为 {@code ~/.easyClaw/db}（{@link SystemHomePaths#dbWorkspaceRoot()}）；</li>
 *   <li>type=db（创建后不可变，Agent 装配据此收缩为最小 toolkit）；</li>
 *   <li>默认绑定内置数据库场景（标识名 {@code db}）。</li>
 * </ul>
 * 每次启动幂等执行：目录缺失则创建、workspace 行缺失则落库、场景未绑定则补绑。
 * 该 workspace 不在前端工作区列表展示，前端数据库入口以固定 id 直接访问；
 * 用户不能创建/删除数据库工作区。
 */
@Component
@Order(21)
public class DefaultDbWorkspaceInitializer {

    private static final Logger log = LoggerFactory.getLogger(DefaultDbWorkspaceInitializer.class);

    /** 内置数据库场景标识名（见 SystemDataSeeder，mode=db；统一锚点见 BuiltinDbIds） */
    private static final String DB_SCENARIO_NAME = BuiltinDbIds.DB;

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceScenarioRepository workspaceScenarioRepository;
    private final ScenarioRepository scenarioRepository;
    private final WorkspaceFileLayout fileLayout;

    public DefaultDbWorkspaceInitializer(WorkspaceRepository workspaceRepository,
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
            Path dbPath = SystemHomePaths.dbWorkspaceRoot();
            Files.createDirectories(dbPath);
            // 与普通工作区一致的磁盘结构（.easyClaw/agent/state 等），幂等不破坏用户数据
            fileLayout.initialize(dbPath, dbPath.resolve(".easyClaw"));
            upsertWorkspace(dbPath);
            bindDbScenario();
            log.info("默认数据库工作区已就绪: id={}, path={}",
                    AppConstants.DEFAULT_DB_WORKSPACE_ID, dbPath);
        } catch (IOException e) {
            // 不阻断启动：数据库功能此时不可用，下次启动会重试
            log.warn("默认数据库工作区初始化失败（数据库功能暂不可用）: {}", e.getMessage());
        }
    }

    /**
     * 落库默认数据库 workspace 行（已存在则只校正关键内置字段，不动 projectId 等用户态字段）。
     * 不加 @Transactional：本方法仅由 {@link #init()}（@PostConstruct）自调用，Spring 代理
     * 被绕过、注解本就不生效；各 save 独立自动提交，幂等启动语义也不需要跨语句原子性。
     */
    public void upsertWorkspace(Path dbPath) {
        WorkspaceEntity entity = workspaceRepository
                .findById(AppConstants.DEFAULT_DB_WORKSPACE_ID)
                .orElseGet(() -> WorkspaceEntity.builder()
                        .id(AppConstants.DEFAULT_DB_WORKSPACE_ID)
                        .userId(AppConstants.DEFAULT_USER_ID)
                        .name("数据库工作区")
                        .createdAt(Instant.now())
                        .build());
        entity.setPath(dbPath.toString());
        entity.setType(BuiltinDbIds.DB);
        entity.setStatus("active");
        entity.setLastAccessedAt(Instant.now());
        workspaceRepository.save(entity);
    }

    /** 绑定内置数据库场景（已绑定则不重复写）；事务口径同 {@link #upsertWorkspace}。 */
    public void bindDbScenario() {
        if (workspaceScenarioRepository
                .findByWorkspaceId(AppConstants.DEFAULT_DB_WORKSPACE_ID).isPresent()) {
            return;
        }
        ScenarioEntity dbScenario = scenarioRepository.findByName(DB_SCENARIO_NAME).orElse(null);
        if (dbScenario == null || !Boolean.TRUE.equals(dbScenario.getActive())) {
            log.warn("内置数据库场景不存在或已停用，默认数据库工作区暂未绑定场景（下次启动重试）");
            return;
        }
        workspaceScenarioRepository.save(
                com.xinl.easyclaw.workspace.entity.WorkspaceScenarioEntity.builder()
                        .workspaceId(AppConstants.DEFAULT_DB_WORKSPACE_ID)
                        .scenarioId(dbScenario.getId())
                        .build());
    }
}
