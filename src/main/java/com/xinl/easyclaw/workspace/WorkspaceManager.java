package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.config.AppConstants;
import com.xinl.easyclaw.config.SystemHomePaths;

import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.role.service.RoleManagementService;
import com.xinl.easyclaw.workspace.entity.SessionEntity;
import com.xinl.easyclaw.workspace.entity.WorkspaceEntity;
import com.xinl.easyclaw.workspace.repository.SessionRepository;
import com.xinl.easyclaw.workspace.repository.WorkspaceRepository;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Workspace 管理器
 * <p>
 * 负责 Workspace 的创建、配置、销毁，以及 Session 的管理。
 * 每个 Workspace 拥有独立的 HarnessAgent 实例，装配：
 * <ul>
 *   <li>独立 Toolkit（内置工具 + 已连接 MCP 工具）</li>
 *   <li>AgentScope 状态存储（{@code .easyClaw/agent/state}，按 userId/sessionId 持久化）</li>
 *   <li>.easyClaw/agent 下的 AGENTS.md / MEMORY.md / skills / subagents（AgentScope 自动加载）</li>
 * </ul>
 */
@Service
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private final WorkspaceRepository workspaceRepository;
    private final SessionRepository sessionRepository;
    private final AgentFactory agentFactory;
    private final PermissionRuleService permissionRuleService;
    private final com.xinl.easyclaw.config.AgentScopeProperties agentScopeProperties;
    private final com.xinl.easyclaw.workspace.repository.WorkspaceScenarioRepository workspaceScenarioRepository;
    /** 磁盘布局维护（目录/模板/迁移）——文件系统关注点已从本类剥离 */
    private final WorkspaceFileLayout fileLayout;
    /** Agent 装配（提示词/模型/权限/沙箱）——Agent 构建关注点已从本类剥离 */
    private final WorkspaceAgentBuilder agentBuilder;

    private final Map<String, WorkspaceContext> workspaceCache = new ConcurrentHashMap<>();
    private final Map<String, SessionContext> sessionCache = new ConcurrentHashMap<>();

    public WorkspaceManager(WorkspaceRepository workspaceRepository,
                            SessionRepository sessionRepository,
                            AgentFactory agentFactory,
                            PermissionRuleService permissionRuleService,
                            com.xinl.easyclaw.config.AgentScopeProperties agentScopeProperties,
                            com.xinl.easyclaw.workspace.repository.WorkspaceScenarioRepository workspaceScenarioRepository,
                            WorkspaceFileLayout fileLayout,
                            WorkspaceAgentBuilder agentBuilder) {
        this.workspaceRepository = workspaceRepository;
        this.sessionRepository = sessionRepository;
        this.agentFactory = agentFactory;
        this.permissionRuleService = permissionRuleService;
        this.agentScopeProperties = agentScopeProperties;
        this.workspaceScenarioRepository = workspaceScenarioRepository;
        this.fileLayout = fileLayout;
        this.agentBuilder = agentBuilder;
    }

    /**
     * 启动迁移：历史版本的用户 ID（default-user）统一迁移为 local，
     * 使 harness 运行时数据目录（{@code <workspace>/.easyClaw/agent/<userId>/agents}）
     * 落在 {@code .easyClaw/agent/} 下，符合目录规范。
     */
    @PostConstruct
    public void migrateLegacyUserId() {
        try {
            int n = workspaceRepository.migrateUserId(AppConstants.LEGACY_USER_ID, AppConstants.DEFAULT_USER_ID);
            if (n > 0) {
                log.info("已迁移 {} 个 Workspace 的用户 ID: {} → {}", n, AppConstants.LEGACY_USER_ID, AppConstants.DEFAULT_USER_ID);
            }
        } catch (Exception e) {
            log.warn("用户 ID 迁移失败（可忽略）: {}", e.getMessage());
        }
    }

    // ==================== Workspace 管理 ====================

    public WorkspaceContext createWorkspace(String userId, String name, String description, String customPath) {
        if (workspaceRepository.existsByPath(customPath)) {
            throw new WorkspaceExceptions.WorkspacePathExistsException(
                    "目录已被占用: " + customPath + "，请选择其他目录");
        }

        Path workspacePath = Paths.get(customPath);
        if (!Files.exists(workspacePath)) {
            throw new WorkspaceExceptions.WorkspacePathNotFoundException(
                    "目录不存在: " + customPath + "，请确认路径是否正确");
        }
        if (!Files.isDirectory(workspacePath)) {
            throw new WorkspaceExceptions.WorkspacePathNotDirectoryException(
                    "路径不是目录: " + customPath);
        }
        if (!Files.isWritable(workspacePath)) {
            throw new WorkspaceExceptions.WorkspacePathNotWritableException(
                    "目录不可写: " + customPath + "，请检查权限");
        }

        String workspaceId = generateWorkspaceId(name);

        // 按 Easy-Claw 规范初始化：AGENTS.md/MEMORY.md/skills/subagents/state 全部集中在 .easyClaw/agent
        Path easyClawDir = workspacePath.resolve(".easyClaw");
        fileLayout.initialize(workspacePath, easyClawDir);

        HarnessAgent agent = agentBuilder.build(workspaceId, name, workspacePath, easyClawDir, null);

        WorkspaceContext context = WorkspaceContext.builder()
                .workspaceId(workspaceId)
                .userId(userId)
                .name(name)
                .description(description)
                .path(workspacePath)
                .agent(agent)
                .createdAt(Instant.now())
                .lastAccessed(Instant.now())
                .build();

        workspaceCache.put(workspaceId, context);

        saveWorkspaceMetadata(context);

        log.info("Workspace 创建成功: id={}, path={}, model={}",
                workspaceId, customPath, agentFactory.getModelId());
        return context;
    }

    /**
     * 工作区级子 Agent 声明目录（{@code <workspace>/.easyClaw/agent/subagents}）
     * <p>
     * 供编排 UI 与 {@code SubagentLoader} 共用同一路径口径，避免「UI 名单」与
     * 「运行时实际加载」不一致。工作区不存在时返回 null。
     */
    public Path subagentsDir(String workspaceId) {
        WorkspaceContext context = getWorkspace(workspaceId);
        return context == null ? null : context.getPath().resolve(".easyClaw/agent/subagents");
    }

    public WorkspaceContext getWorkspace(String workspaceId) {
        // computeIfAbsent 保证同一 workspaceId 只会构建一个 Agent：
        // 原先 get→restore→put 的非原子序列在并发首访时会各自建一个 Agent，
        // 导致多个实例竞争同一状态文件（code-review Finding 9）
        WorkspaceContext context = workspaceCache.computeIfAbsent(workspaceId, this::restoreWorkspace);
        // 注意：不在此处补齐模板文件 —— getWorkspace 是每轮对话的热路径，
        // 读操作不应带同步磁盘写。模板补齐改由创建流程与 repairWorkspaceFiles 显式触发。
        if (context != null) {
            context.setLastAccessed(Instant.now());
        }
        return context;
    }

    /**
     * 显式修复工作区模板文件（AGENTS.md / MEMORY.md 等被用户误删时调用）。
     * 从 {@link #getWorkspace} 热路径中剥离出来，避免每轮对话都做文件系统检查。
     */
    public void repairWorkspaceFiles(String workspaceId) {
        WorkspaceContext context = getWorkspace(workspaceId);
        if (context != null) {
            fileLayout.repair(context.getPath().resolve(".easyClaw/agent"));
        }
    }

    /**
     * 从磁盘恢复 Workspace（服务重启后自动重建 Agent，状态由 AgentStateStore 按会话恢复）
     */
    private WorkspaceContext restoreWorkspace(String workspaceId) {
        return restoreWorkspace(workspaceId, null);
    }

    private WorkspaceContext restoreWorkspace(String workspaceId, String sysPromptAugment) {
        WorkspaceEntity meta = workspaceRepository.findById(workspaceId).orElse(null);
        if (meta == null) {
            return null;
        }

        Path workspacePath = Paths.get(meta.getPath());
        if (!Files.exists(workspacePath)) {
            log.warn("Workspace 目录不存在，跳过恢复: {}", meta.getPath());
            return null;
        }

        Path easyClawDir = workspacePath.resolve(".easyClaw");
        fileLayout.initialize(workspacePath, easyClawDir);

        HarnessAgent agent = agentBuilder.build(workspaceId, meta.getName(), workspacePath, easyClawDir, sysPromptAugment);

        return WorkspaceContext.builder()
                .workspaceId(workspaceId)
                .userId(meta.getUserId())
                .name(meta.getName())
                .description(meta.getDescription())
                .path(workspacePath)
                .agent(agent)
                .createdAt(meta.getCreatedAt())
                .lastAccessed(Instant.now())
                .restored(true)
                .build();
    }

    /**
     * 重建 Workspace 的 Agent（Skill / 子 Agent 声明修改后调用，使新声明生效）
     */
    public void rebuildAgent(String workspaceId) {
        rebuildAgent(workspaceId, null);
    }

    /**
     * 重建 Workspace 的 Agent，并将额外的 prompt 片段注入到 system prompt 中
     * （用于每轮对话注入模式/Skill，避免污染用户消息）。
     */
    public void rebuildAgent(String workspaceId, String sysPromptAugment) {
        // compute 原子替换：remove→close→restore→put 的非原子序列存在竞态窗口，
        // 期间并发 getWorkspace 会看到缓存缺失并各自重建 Agent（code-review Finding 9）
        WorkspaceContext rebuilt = workspaceCache.compute(workspaceId, (id, old) -> {
            if (old != null) {
                // 关闭旧 Agent：触发状态落盘 + 释放资源（消息总线监听等），
                // 避免 rebuild 后旧实例泄漏、或未落盘的会话状态丢失
                try {
                    old.getAgent().close();
                } catch (Exception e) {
                    log.warn("重建前关闭旧 Agent 失败（忽略）: {}", e.getMessage());
                }
            }
            // 返回 null 表示恢复失败 → 该 key 从缓存移除，下次 getWorkspace 再尝试
            return restoreWorkspace(id, sysPromptAugment);
        });
        if (rebuilt != null) {
            log.info("Workspace Agent 已重建: {}, augment={}", workspaceId,
                    sysPromptAugment == null ? "none" : sysPromptAugment.length() + " chars");
        }
    }

    /**
     * 重建所有已加载 Workspace 的 Agent（工具启用/禁用后调用，使 Toolkit 过滤立即生效）
     */
    public void rebuildAllAgents() {
        for (String id : new java.util.ArrayList<>(workspaceCache.keySet())) {
            rebuildAgent(id);
        }
        log.info("已重建 {} 个 Workspace 的 Agent", workspaceCache.size());
    }

    /**
     * 定时驱逐空闲/超量的 Workspace Agent 缓存。
     * <p>
     * {@code WorkspaceContext} 持有 HarnessAgent（模型客户端、工具集、MCP 连接），
     * 原先 {@code lastAccessed} 虽在维护却从未用于驱逐，导致句柄与内存随工作区数量
     * 只增不减（code-review Finding 9）。此处按两条规则回收：
     * <ol>
     *   <li>空闲超过 {@code workspace-idle-minutes} 未访问；</li>
     *   <li>总数超过 {@code max-cached-workspaces} 时，按 lastAccessed 最旧优先淘汰。</li>
     * </ol>
     * 驱逐前调用 {@code close()} 触发状态落盘，因此被驱逐的工作区下次访问会自动重建，
     * 不会丢失会话状态。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000L)
    public void sweepWorkspaceCache() {
        int idleMinutes = agentScopeProperties.getAgent().getWorkspaceIdleMinutes();
        int maxCached = agentScopeProperties.getAgent().getMaxCachedWorkspaces();

        // 规则 1：空闲驱逐
        if (idleMinutes > 0) {
            Instant deadline = Instant.now().minusSeconds(idleMinutes * 60L);
            for (Map.Entry<String, WorkspaceContext> e : new java.util.ArrayList<>(workspaceCache.entrySet())) {
                Instant last = e.getValue().getLastAccessed();
                if (last != null && last.isBefore(deadline)) {
                    evictFromCache(e.getKey(), "空闲超过 " + idleMinutes + " 分钟");
                }
            }
        }

        // 规则 2：超量驱逐（最旧优先）
        if (maxCached > 0 && workspaceCache.size() > maxCached) {
            List<Map.Entry<String, WorkspaceContext>> byOldest =
                    new java.util.ArrayList<>(workspaceCache.entrySet());
            byOldest.sort(java.util.Comparator.comparing(
                    e -> e.getValue().getLastAccessed() == null ? Instant.EPOCH : e.getValue().getLastAccessed()));
            int overflow = workspaceCache.size() - maxCached;
            for (int i = 0; i < overflow && i < byOldest.size(); i++) {
                evictFromCache(byOldest.get(i).getKey(), "缓存超过上限 " + maxCached);
            }
        }
    }

    /**
     * 从缓存移除并关闭 Agent。用 {@code computeIfPresent} 保证「取出 + 关闭」原子，
     * 避免与 {@link #rebuildAgent} / {@link #getWorkspace} 并发时关闭到别人刚建好的实例。
     */
    private void evictFromCache(String workspaceId, String reason) {
        workspaceCache.computeIfPresent(workspaceId, (id, ctx) -> {
            try {
                ctx.getAgent().close();
            } catch (Exception e) {
                log.warn("驱逐时关闭 Agent 失败（忽略）: workspace={}, err={}", id, e.getMessage());
            }
            log.info("已驱逐 Workspace Agent 缓存: {}（{}）", id, reason);
            return null; // 返回 null → 从 Map 中移除
        });
    }

    /**
     * 删除工作区，并联动清理会话记录、场景激活关系、工具白名单。
     * <p>
     * 注意：方法整体在事务内，各步骤不再 try/catch 吞异常——一旦某步失败必须整体回滚，
     * 否则会出现「部分清理成功 + 提交期抛 UnexpectedRollbackException」的静默删除失败。
     * 磁盘上的项目文件与 Agent 状态目录不会被删除。
     */
    @Transactional
    public void deleteWorkspace(String workspaceId) {
        WorkspaceContext context = workspaceCache.remove(workspaceId);
        // 工作区可能未加载进缓存（如重启后未访问过），缓存命中只决定是否 close Agent，
        // DB 清理必须无条件执行，否则会残留工作区记录（表现为「删除不生效」）
        if (context != null) {
            try {
                context.getAgent().close();
            } catch (Exception e) {
                // 仅关闭 Agent 的异常可容忍：属于内存资源释放，不影响删除的数据一致性
                log.warn("关闭 Agent 时出现异常: {}", e.getMessage());
            }
        }

        // 清理内存中该工作区的会话，避免悬挂 SessionContext 持有已关闭的 Agent
        sessionCache.entrySet().removeIf(e -> workspaceId.equals(e.getValue().getWorkspaceId()));

        // 先清子表再删主表，避免外键/悬挂数据
        List<SessionEntity> sessions = sessionRepository.findByWorkspaceId(workspaceId);
        sessionRepository.deleteAll(sessions);
        workspaceScenarioRepository.deleteByWorkspaceId(workspaceId);
        int removedRules = permissionRuleService.removeAll(workspaceId);
        workspaceRepository.deleteById(workspaceId);

        log.info("Workspace 已删除: {}（清理 {} 条会话、{} 条权限规则）",
                workspaceId, sessions.size(), removedRules);
    }

    /**
     * 更新工作区基本信息（名称/描述）。路径不可改，避免 Agent 沙箱根目录漂移。
     */
    @Transactional
    public WorkspaceSummary updateWorkspace(String workspaceId, String name, String description) {
        WorkspaceEntity entity = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("工作区不存在: " + workspaceId));
        if (name != null && !name.isBlank()) {
            entity.setName(name.trim());
        }
        if (description != null) {
            entity.setDescription(description);
        }
        entity.setUpdatedAt(Instant.now());
        WorkspaceEntity saved = workspaceRepository.save(entity);
        // 同步缓存，避免下次读到旧名称
        WorkspaceContext ctx = workspaceCache.get(workspaceId);
        if (ctx != null) {
            ctx.setName(saved.getName());
            ctx.setDescription(saved.getDescription());
        }
        log.info("Workspace 已更新: id={}, name={}", workspaceId, saved.getName());
        return toSummary(saved);
    }

    public List<WorkspaceSummary> getUserWorkspaces(String userId) {
        return workspaceRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    public PathValidationResult validatePath(String path) {
        PathValidationResult result = new PathValidationResult();

        if (workspaceRepository.existsByPath(path)) {
            result.setValid(false);
            result.setMessage("该目录已被其他 Workspace 占用");
            return result;
        }

        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            result.setValid(false);
            result.setMessage("目录不存在，请创建该目录后再试");
            return result;
        }
        if (!Files.isDirectory(p)) {
            result.setValid(false);
            result.setMessage("路径不是目录");
            return result;
        }
        if (!Files.isWritable(p)) {
            result.setValid(false);
            result.setMessage("目录不可写，请检查权限");
            return result;
        }

        result.setValid(true);
        result.setMessage("目录可用");
        return result;
    }

    // ==================== Session 管理 ====================

    public SessionContext createSession(String workspaceId, String sessionId, String title) {
        WorkspaceContext workspace = getWorkspace(workspaceId);
        if (workspace == null) {
            throw new WorkspaceExceptions.WorkspaceNotFoundException("Workspace 未找到: " + workspaceId);
        }

        SessionContext session = SessionContext.builder()
                .sessionId(sessionId)
                .workspaceId(workspaceId)
                .title(title)
                .agent(workspace.getAgent())
                .createdAt(Instant.now())
                .lastAccessed(Instant.now())
                .build();

        sessionCache.put(sessionId, session);

        SessionEntity entity = SessionEntity.builder()
                .id(sessionId)
                .workspaceId(workspaceId)
                .title(title)
                .createdAt(Instant.now())
                .build();
        sessionRepository.save(entity);

        return session;
    }

    /**
     * 同步会话缓存中的标题。
     * <p>
     * 持久化由 {@code SessionHistoryService} 负责，这里只更新内存缓存：
     * 会话未被缓存时无需处理，下次 {@link #getSession} 会从库里读到新标题。
     */
    public void updateSessionTitle(String sessionId, String title) {
        SessionContext cached = sessionCache.get(sessionId);
        if (cached != null) {
            cached.setTitle(title);
        }
    }

    public SessionContext getSession(String sessionId) {        SessionContext session = sessionCache.get(sessionId);
        if (session == null) {
            SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
            if (entity != null) {
                WorkspaceContext workspace = getWorkspace(entity.getWorkspaceId());
                if (workspace != null) {
                    session = SessionContext.builder()
                            .sessionId(entity.getId())
                            .workspaceId(entity.getWorkspaceId())
                            .title(entity.getTitle())
                            .agent(workspace.getAgent())
                            .createdAt(entity.getCreatedAt())
                            .lastAccessed(Instant.now())
                            .restored(true)
                            .build();
                    sessionCache.put(sessionId, session);
                }
            }
        }
        if (session != null) {
            session.setLastAccessed(Instant.now());
        }
        return session;
    }

    // ==================== 内部方法 ====================

    private void saveWorkspaceMetadata(WorkspaceContext ctx) {
        WorkspaceEntity entity = WorkspaceEntity.builder()
                .id(ctx.getWorkspaceId())
                .userId(ctx.getUserId())
                .name(ctx.getName())
                .description(ctx.getDescription())
                .path(ctx.getPath().toString())
                .status("active")
                .createdAt(ctx.getCreatedAt())
                .lastAccessedAt(Instant.now())
                .build();
        workspaceRepository.save(entity);
    }

    private WorkspaceSummary toSummary(WorkspaceEntity entity) {
        return WorkspaceSummary.builder()
                .workspaceId(entity.getId())
                .name(entity.getName())
                .agentName(agentBuilder.resolveAgentDisplayName(entity.getName()))
                .description(entity.getDescription())
                .path(entity.getPath())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .lastAccessed(entity.getLastAccessedAt())
                .build();
    }

    private String generateWorkspaceId(String name) {
        String timestamp = java.time.ZonedDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String safeName = name.toLowerCase().replaceAll("[^a-z0-9]", "-");
        return safeName + "-" + timestamp;
    }
}
