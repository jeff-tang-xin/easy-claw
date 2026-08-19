package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.agent.embabel.PresetIntent;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AppConstants;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import com.xinl.easyclaw.role.service.RoleManagementService;
import com.xinl.easyclaw.workspace.entity.SessionEntity;
import com.xinl.easyclaw.workspace.entity.WorkspaceEntity;
import com.xinl.easyclaw.workspace.repository.SessionRepository;
import com.xinl.easyclaw.workspace.repository.WorkspaceRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private final WorkspaceRepository workspaceRepository;
    private final SessionRepository sessionRepository;
    private final AgentFactory agentFactory;
    private final SubagentLoader subagentLoader;
    private final PermissionRuleService permissionRuleService;
    private final RoleManagementService roleService;

    private final Map<String, WorkspaceContext> workspaceCache = new ConcurrentHashMap<>();
    private final Map<String, SessionContext> sessionCache = new ConcurrentHashMap<>();
    private final Map<String, String> refreshedPaths = new ConcurrentHashMap<>();

    public void setRefreshedPath(String workspaceId, String path) {
        if (path != null) {
            refreshedPaths.put(workspaceId, path);
        } else {
            refreshedPaths.remove(workspaceId);
        }
    }

    public String getRefreshedPath(String workspaceId) {
        return refreshedPaths.get(workspaceId);
    }

    /* TODO: migrate to Embabel - AgentScopeProperties removed */
    public WorkspaceManager(WorkspaceRepository workspaceRepository,
                            SessionRepository sessionRepository,
                            AgentFactory agentFactory,
                            SubagentLoader subagentLoader,
                            PermissionRuleService permissionRuleService,
                            RoleManagementService roleService) {
        this.workspaceRepository = workspaceRepository;
        this.sessionRepository = sessionRepository;
        this.agentFactory = agentFactory;
        this.subagentLoader = subagentLoader;
        this.permissionRuleService = permissionRuleService;
        this.roleService = roleService;
    }

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

    public WorkspaceContext createWorkspace(String userId, String name, String description, String customPath,
                                            String intent, List<String> activeSkills, String scenarioId) {
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
        String normalizedIntent = normalizeIntent(intent);
        List<String> finalSkills = (activeSkills != null && !activeSkills.isEmpty())
                ? new ArrayList<>(activeSkills)
                : PresetIntent.resolveActiveSkills(normalizedIntent, null);
        // 如果前端传了 scenarioId（包括自定义场景），直接用；否则按 intent 自动映射
        String finalScenarioId = (scenarioId != null && !scenarioId.isBlank())
                ? scenarioId
                : intentToScenarioId(normalizedIntent);

        Path easyClawDir = workspacePath.resolve(".easyClaw");
        initWorkspaceStructure(workspacePath, easyClawDir);

        WorkspaceContext context = WorkspaceContext.builder()
                .workspaceId(workspaceId)
                .userId(userId)
                .name(name)
                .description(description)
                .path(workspacePath)
                .createdAt(Instant.now())
                .lastAccessed(Instant.now())
                .intent(normalizedIntent)
                .activeSkills(finalSkills)
                .scenarioId(finalScenarioId)
                .build();

        workspaceCache.put(workspaceId, context);

        saveWorkspaceMetadata(context, normalizedIntent, finalScenarioId, finalSkills);

        log.info("Workspace 创建成功: id={}, path={}, intent={}, scenarioId={}, skills={}",
                workspaceId, customPath, normalizedIntent, finalScenarioId, finalSkills);
        return context;
    }

    // ===================== Intent / ScenarioId 映射 =====================
    /** intent 合法性归一：不在 PresetIntent.INTENT_META 中的一律回退 GENERAL，避免拼错 scenarioId */
    static String normalizeIntent(String intent) {
        if (intent == null || intent.isBlank()) return PresetIntent.GENERAL;
        Set<String> valid = new LinkedHashSet<>(PresetIntent.INTENT_META.keySet());
        return valid.contains(intent) ? intent : PresetIntent.GENERAL;
    }

    /** intent → scenarioId（和 ScenarioService.normalizePresets 中第一个参数 id 完全对应：preset_general / preset_coding / preset_mail-triage ...） */
    static String intentToScenarioId(String intent) {
        return "preset_" + normalizeIntent(intent);
    }

    static String serializeActiveSkills(List<String> skills) {
        if (skills == null) return null;
        try { return MAPPER.writeValueAsString(skills); }
        catch (Exception e) { return "[]"; }
    }

    public WorkspaceContext getWorkspace(String workspaceId) {
        WorkspaceContext context = workspaceCache.get(workspaceId);
        if (context == null) {
            context = restoreWorkspace(workspaceId);
            if (context != null) {
                workspaceCache.put(workspaceId, context);
            }
        } else {
            ensureWorkspaceFiles(context.getPath().resolve(".easyClaw/agent"));
        }
        if (context != null) {
            context.setLastAccessed(Instant.now());
        }
        return context;
    }

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
        initWorkspaceStructure(workspacePath, easyClawDir);

        // ===================== 旧库兼容：空 scenarioId 自动根据 intent 修复写库 =====================
        String intent = resolveIntent(meta);
        String expectedScenarioId = intentToScenarioId(intent);
        String scenarioId = meta.getScenarioId();
        boolean scenarioBroken = scenarioId == null || scenarioId.isBlank() || !scenarioId.equals(expectedScenarioId);
        if (scenarioBroken) {
            log.info("Workspace 场景不匹配/缺失，自动修复: id={}, oldScenarioId={}, intent={}, fix→{}",
                    workspaceId, scenarioId, intent, expectedScenarioId);
            meta.setScenarioId(expectedScenarioId);
            scenarioId = expectedScenarioId;
            if (meta.getIntent() == null || meta.getIntent().isBlank()) meta.setIntent(intent);
            meta.setUpdatedAt(Instant.now());
            workspaceRepository.save(meta);
        }
        List<String> activeSkills = resolveActiveSkills(meta);

        return WorkspaceContext.builder()
                .workspaceId(workspaceId)
                .userId(meta.getUserId())
                .name(meta.getName())
                .description(meta.getDescription())
                .path(workspacePath)
                .createdAt(meta.getCreatedAt())
                .lastAccessed(Instant.now())
                .restored(true)
                .intent(intent)
                .activeSkills(activeSkills)
                .scenarioId(scenarioId)
                .build();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String resolveIntent(WorkspaceEntity meta) {
        String intent = meta.getIntent();
        return intent == null || intent.isBlank() ? PresetIntent.GENERAL : intent;
    }

    private List<String> resolveActiveSkills(WorkspaceEntity meta) {
        String json = meta.getActiveSkills();
        if (json == null || json.isBlank()) return PresetIntent.resolveActiveSkills(resolveIntent(meta), null);
        try {
            List<String> parsed = MAPPER.readValue(json, new TypeReference<>() {});
            if (parsed != null && !parsed.isEmpty()) return parsed;
        } catch (Exception ignored) {}
        return PresetIntent.resolveActiveSkills(resolveIntent(meta), null);
    }

    public void rebuildAgent(String workspaceId) {
        rebuildAgent(workspaceId, null);
    }

    public void rebuildAgent(String workspaceId, String sysPromptAugment) {
        // Embabel 架构下 Agent 由 RoleAgentFactory 按需解析，rebuild 仅刷新 workspaceCache 确保配置变更生效
        workspaceCache.remove(workspaceId);
        WorkspaceContext rebuilt = restoreWorkspace(workspaceId, sysPromptAugment);
        if (rebuilt != null) {
            workspaceCache.put(workspaceId, rebuilt);
            log.info("Workspace cache refreshed: {}, augment={}", workspaceId,
                    sysPromptAugment == null ? "none" : sysPromptAugment.length() + " chars");
        }
    }

    public void rebuildAllAgents() {
        // Embabel 架构下：清除所有 workspace cache 让下次请求拿到最新配置
        for (String id : new java.util.ArrayList<>(workspaceCache.keySet())) {
            rebuildAgent(id);
        }
        log.info("Refreshed {} Workspace caches", workspaceCache.size());
    }

    public void deleteWorkspace(String workspaceId) {
        WorkspaceContext context = workspaceCache.remove(workspaceId);
        if (context != null) {
            /* TODO: migrate to Embabel - agent.close() disabled */
            workspaceRepository.deleteById(workspaceId);
            log.info("Workspace 已删除: {}", workspaceId);
        }
    }

    public List<WorkspaceSummary> getUserWorkspaces(String userId) {
        return workspaceRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    public WorkspaceSummary getWorkspaceSummary(String workspaceId) {
        WorkspaceEntity entity = workspaceRepository.findById(workspaceId).orElse(null);
        if (entity == null) return null;
        return toSummary(entity);
    }

    public WorkspaceSummary updateIntent(String workspaceId, String intent, List<String> activeSkills, String scenarioId) {
        WorkspaceEntity entity = workspaceRepository.findById(workspaceId).orElse(null);
        if (entity == null) return null;
        String normalized = normalizeIntent(intent);
        entity.setIntent(normalized);
        // 如果前端传了 scenarioId（包括自定义场景），直接用；否则按 intent 自动映射
        String finalScenarioId = (scenarioId != null && !scenarioId.isBlank())
                ? scenarioId
                : intentToScenarioId(normalized);
        entity.setScenarioId(finalScenarioId);
        if (activeSkills == null) {
            entity.setActiveSkills(serializeActiveSkills(PresetIntent.resolveActiveSkills(normalized, null)));
        } else {
            try {
                entity.setActiveSkills(serializeActiveSkills(activeSkills));
            } catch (Exception e) {
                log.warn("序列化 activeSkills 失败", e);
            }
        }
        entity.setUpdatedAt(java.time.Instant.now());
        workspaceRepository.save(entity);

        WorkspaceContext ctx = workspaceCache.get(workspaceId);
        if (ctx != null) {
            ctx.setIntent(normalized);
            ctx.setScenarioId(finalScenarioId);
            if (activeSkills != null) ctx.setActiveSkills(new ArrayList<>(activeSkills));
            else ctx.setActiveSkills(PresetIntent.resolveActiveSkills(normalized, null));
        }
        return toSummary(entity);
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

    public SessionContext createSession(String workspaceId, String sessionId, String title) {
        WorkspaceContext workspace = getWorkspace(workspaceId);
        if (workspace == null) {
            throw new WorkspaceExceptions.WorkspaceNotFoundException("Workspace 未找到: " + workspaceId);
        }

        SessionContext session = SessionContext.builder()
                .sessionId(sessionId)
                .workspaceId(workspaceId)
                .title(title)
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

    public SessionContext getSession(String sessionId) {
        SessionContext session = sessionCache.get(sessionId);
        if (session == null) {
            SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
            if (entity != null) {
                WorkspaceContext workspace = getWorkspace(entity.getWorkspaceId());
                if (workspace != null) {
                    session = SessionContext.builder()
                            .sessionId(entity.getId())
                            .workspaceId(entity.getWorkspaceId())
                            .title(entity.getTitle())
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

    private void saveWorkspaceMetadata(WorkspaceContext ctx) {
        // 兼容模式：如果调用方没传 intent/scenarioId，根据 ctx.intent 兜底
        String intent = ctx.getIntent() != null ? ctx.getIntent() : PresetIntent.GENERAL;
        String scenarioId = ctx.getScenarioId() != null ? ctx.getScenarioId() : intentToScenarioId(intent);
        List<String> skills = ctx.getActiveSkills() != null ? ctx.getActiveSkills() : PresetIntent.resolveActiveSkills(intent, null);
        saveWorkspaceMetadata(ctx, intent, scenarioId, skills);
    }

    private void saveWorkspaceMetadata(WorkspaceContext ctx, String intent, String scenarioId, List<String> activeSkills) {
        WorkspaceEntity entity = WorkspaceEntity.builder()
                .id(ctx.getWorkspaceId())
                .userId(ctx.getUserId())
                .name(ctx.getName())
                .description(ctx.getDescription())
                .path(ctx.getPath().toString())
                .status("active")
                .intent(intent == null || intent.isBlank() ? PresetIntent.GENERAL : intent)
                .scenarioId(scenarioId)
                .activeSkills(serializeActiveSkills(activeSkills))
                .createdAt(ctx.getCreatedAt())
                .lastAccessedAt(Instant.now())
                .build();
        workspaceRepository.save(entity);
    }

    private WorkspaceSummary toSummary(WorkspaceEntity entity) {
        String intent = entity.getIntent();
        if (intent == null || intent.isBlank()) intent = PresetIntent.GENERAL;
        List<String> skills = resolveActiveSkills(entity);
        return WorkspaceSummary.builder()
                .workspaceId(entity.getId())
                .name(entity.getName())
                .agentName(resolveAgentDisplayName(entity.getName()))
                .description(entity.getDescription())
                .path(Path.of(entity.getPath()))
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .lastAccessed(entity.getLastAccessedAt())
                .intent(intent)
                .scenarioId(entity.getScenarioId())
                .activeSkills(skills)
                .build();
    }

    private String resolveAgentDisplayName(String fallback) {
        try {
            AgentRoleEntity main = roleService.findByName("main").orElse(null);
            if (main != null && main.getDisplayName() != null && !main.getDisplayName().isBlank()) {
                return main.getDisplayName();
            }
        } catch (Exception e) {
            log.debug("读取主角色 displayName 失败，用 workspace name: {}", e.getMessage());
        }
        return fallback;
    }

    private void ensureWorkspaceFiles(Path agentDir) {
        try {
            Files.createDirectories(agentDir);
            Files.createDirectories(agentDir.resolve("state"));
            Files.createDirectories(agentDir.resolve("skills"));
            Files.createDirectories(agentDir.resolve("subagents"));

            Path agentsFile = agentDir.resolve("AGENTS.md");
            if (!Files.exists(agentsFile)) {
                Files.writeString(agentsFile, """
                        # AI 编程助手工作规范

                        ## 角色
                        你是 Easy-Claw AI 编程助手，当前工作区的主控 Agent。你拥有代码编写、文件操作、网络搜索、MCP 扩展工具等能力，并可调度专项子 Agent 协同完成复杂任务。

                        ## 目标
                        帮助用户高效完成当前工作区的编程与文件任务，包括但不限于：功能开发、Bug 修复、代码重构、代码审查、项目配置、文件批量处理、资料检索与分析。

                        ## 工作空间（最重要）
                        - 你的一切文件操作都限制在当前工作区内（用户指定的项目目录）
                        - 所有路径基于工作区根目录，使用相对路径（如 src/main/...），禁止访问工作区之外的任何路径
                        - 系统目录 `.easyClaw/` 存放 Agent 配置与运行时数据，**不要修改或删除**，包括：
                          - `.easyClaw/agent/subagents/` — 子 Agent 声明文件
                          - `.easyClaw/agent/skills/` — 技能定义与操作指南
                          - `.easyClaw/agent/state/` — 会话状态存储
                        - 优先使用工作区内已有工具完成任务，避免引入不必要的外部依赖

                        ## 子 Agent 编排
                        你可以调度专项子 Agent 在同一工作区内协同工作，当前可用的子 Agent 及其职责由系统动态注入。调度原则：
                        - 当任务适合交给专项子 Agent 时（如大量代码审查、深度研究分析），优先调度子 Agent 协同完成，而不是自己硬做
                        - 调度子 Agent 时给出明确的任务目标和输出要求，而不是模糊指令
                        - 同一子 Agent 最多调度 2 次；若子 Agent 无法完成，请自己直接处理，禁止重复调度同一子 Agent
                        - 子 Agent 返回结果后，你负责整合、补充和最终交付

                        ## 行为准则
                        - **专业准确** — 回答有条理，代码可运行，不确定时坦诚说明，不编造信息
                        - **理解先行** — 修改代码前先阅读相关文件，理清上下文和依赖关系，遵循项目已有的命名风格和编码约定
                        - **小步验证** — 每次聚焦一个明确目标，修改后及时验证（编译、测试、lint），确认无副作用再继续
                        - **文件安全** — 批量操作前先列出影响范围；编辑文件保留原有缩进、换行符和编码；大文件使用分页读取
                        - **主动沟通** — 遇到错误先自行排查（读报错、看日志、搜代码），无法解决再询问用户；主动识别用户意图，在合理范围内提供额外价值
                        - **Shell 规范** — Windows 环境使用兼容语法（cmd /c），长命令注意超时，优先用内置工具快速定位
                        """);
                log.info("已生成 AGENTS.md: {}", agentsFile);
            }

            Path memoryFile = agentDir.resolve("MEMORY.md");
            if (!Files.exists(memoryFile)) {
                Files.writeString(memoryFile, """
                        # 工作区记忆

                        > 本文件由 Agent 自动维护，用于沉淀跨会话的重要信息。
                        > 仅在发现有长期价值的内容时更新，不要记录临时或一次性信息。

                        ## 项目概览
                        - 项目类型与技术栈：
                        - 构建工具与命令：
                        - 目录结构说明：

                        ## 代码约定
                        - 命名风格：
                        - 编码规范：
                        - 特殊模式或惯用写法：

                        ## 用户偏好
                        - 语言与输出风格：
                        - 工具使用习惯：
                        - 禁忌或特殊要求：

                        ## 关键决策记录
                        | 日期 | 决策内容 | 原因/背景 |
                        |------|---------|----------|
                        |      |         |          |

                        ## 已知问题与 TODO
                        - [ ] 
                        """);
                log.info("已生成 MEMORY.md: {}", memoryFile);
            }

            Path reviewerFile = agentDir.resolve("subagents").resolve("reviewer.md");
            if (!Files.exists(reviewerFile)) {
                Files.writeString(reviewerFile, """
                        ---
                        description: 代码审查专家，负责检查代码质量、发现 Bug 与改进建议
                        steps: 8
                        tools: [read_file, list_directory, search_files]
                        ---
                        你是一名资深的代码审查专家。收到任务后：
                        1. 先阅读相关文件，理解上下文
                        2. 逐段检查代码质量、潜在 Bug、安全隐患
                        3. 给出带文件与行号的改进建议
                        4. 结论用中文，简洁清晰
                        """);
            }
        } catch (IOException e) {
            log.warn("补齐 Workspace 模板文件失败: {}", agentDir, e);
        }
    }

    private void initWorkspaceStructure(Path workspacePath, Path easyClawDir) {
        try {
            Path agentDir = easyClawDir.resolve("agent");

            Path legacy = workspacePath.resolve(".agentscope");
            if (Files.exists(legacy) && !Files.exists(easyClawDir)) {
                Files.createDirectories(agentDir);
                try (var walk = Files.walk(legacy)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            if (!p.equals(legacy)) {
                                Path target = agentDir.resolve(legacy.relativize(p));
                                Files.createDirectories(target.getParent());
                                Files.move(p, target);
                            }
                        } catch (IOException ignored) {
                        }
                    });
                }
                Files.deleteIfExists(legacy);
                log.info("已迁移旧目录 {} → {}", legacy, agentDir);
            }

            Files.createDirectories(agentDir);
            Files.createDirectories(agentDir.resolve("state"));
            Files.createDirectories(agentDir.resolve("skills"));
            Files.createDirectories(agentDir.resolve("subagents"));

            migrateIfAbsent(workspacePath.resolve("AGENTS.md"), agentDir.resolve("AGENTS.md"));
            migrateIfAbsent(workspacePath.resolve("MEMORY.md"), agentDir.resolve("MEMORY.md"));
            migrateDirIfAbsent(workspacePath.resolve("skills"), agentDir.resolve("skills"));
            migrateDirIfAbsent(workspacePath.resolve("subagents"), agentDir.resolve("subagents"));

            ensureWorkspaceFiles(agentDir);

            cleanupLegacyDirs(workspacePath);
        } catch (IOException e) {
            log.error("初始化 Workspace 结构失败: {}", workspacePath, e);
            throw new RuntimeException("初始化 Workspace 结构失败", e);
        }
    }

    private void migrateIfAbsent(Path src, Path dst) throws IOException {
        if (Files.exists(src) && !Files.exists(dst)) {
            Files.createDirectories(dst.getParent());
            Files.move(src, dst);
            log.info("已迁移 {} → {}", src, dst);
        }
    }

    private void migrateDirIfAbsent(Path src, Path dst) throws IOException {
        if (Files.isDirectory(src) && !Files.exists(dst)) {
            Files.createDirectories(dst);
            try (var walk = Files.walk(src)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        if (!p.equals(src)) {
                            Path target = dst.resolve(src.relativize(p));
                            Files.createDirectories(target.getParent());
                            Files.move(p, target);
                        }
                    } catch (IOException ignored) {
                    }
                });
            }
            Files.deleteIfExists(src);
            log.info("已迁移目录 {} → {}", src, dst);
        }
    }

    private void cleanupLegacyDirs(Path workspacePath) {
        for (String userId : List.of(AppConstants.LEGACY_USER_ID, AppConstants.DEFAULT_USER_ID)) {
            for (Path legacy : List.of(
                    workspacePath.resolve(userId + "/agents"),
                    workspacePath.resolve(userId + "/sessions"),
                    workspacePath.resolve(userId))) {
                if (Files.exists(legacy)) {
                    try {
                        deleteRecursively(legacy);
                        log.info("已清理遗留目录: {}", legacy);
                    } catch (IOException e) {
                        log.warn("清理遗留目录失败 {}: {}", legacy, e.getMessage());
                    }
                }
            }
        }

        Path legacyBus = workspacePath.resolve(".agentscope");
        if (Files.exists(legacyBus)) {
            try {
                deleteRecursively(legacyBus);
                log.info("已清理遗留目录: {}", legacyBus);
            } catch (IOException e) {
                log.warn("清理遗留目录失败 {}: {}", legacyBus, e.getMessage());
            }
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
    }

    private String generateWorkspaceId(String name) {
        String timestamp = java.time.ZonedDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String safeName = name.toLowerCase().replaceAll("[^a-z0-9]", "-");
        return safeName + "-" + timestamp;
    }
}
