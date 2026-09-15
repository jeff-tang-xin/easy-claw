package com.xinl.easyclaw.api;

import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.permission.entity.PermissionRuleEntity;
import com.xinl.easyclaw.workspace.*;
import com.xinl.easyclaw.workspace.entity.SessionEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkspaceController.class);

    /** 内置「通用编程」场景标识（见 SystemDataSeeder），新建工作区的默认场景 */
    private static final String DEFAULT_SCENARIO_NAME = "general-coding";

    private final WorkspaceManager workspaceManager;
    private final SessionHistoryService sessionHistoryService;
    private final AgentService agentService;
    private final WorkspaceSandbox sandbox;
    /** PATH 刷新态由 Agent 装配器持有（Agent 重建时需重新注入 env） */
    private final WorkspaceAgentBuilder agentBuilder;
    private final com.xinl.easyclaw.scenario.service.ScenarioService scenarioService;
    /** 权限规则读取直连 permission 层：AgentService 不持有该数据，不应借道穿透 */
    private final com.xinl.easyclaw.permission.service.PermissionRuleService permissionRuleService;
    /** 会话↔worktree 挂钩：git worktree 的创建/挂载/移除（2026-09-14） */
    private final WorktreeService worktreeService;

    public WorkspaceController(WorkspaceManager workspaceManager,
                               SessionHistoryService sessionHistoryService,
                               AgentService agentService,
                               WorkspaceSandbox sandbox,
                               WorkspaceAgentBuilder agentBuilder,
                               com.xinl.easyclaw.scenario.service.ScenarioService scenarioService,
                               com.xinl.easyclaw.permission.service.PermissionRuleService permissionRuleService,
                               WorktreeService worktreeService) {
        this.workspaceManager = workspaceManager;
        this.sessionHistoryService = sessionHistoryService;
        this.agentService = agentService;
        this.sandbox = sandbox;
        this.agentBuilder = agentBuilder;
        this.scenarioService = scenarioService;
        this.permissionRuleService = permissionRuleService;
        this.worktreeService = worktreeService;
    }

    /**
     * @param type         工作区形态分类 single / team / schedule；缺省 single。
     *                     决定可绑定的场景类型（必须一致）。
     * @param scenarioName 场景标识名；前端为必填项，缺省时回退内置「通用编程」，
     *                     保证任何工作区创建后都处于明确的场景约束下
     */
    public record CreateWorkspaceRequest(String name, String description, String path,
                                         String scenarioName, String type) {
    }

    public record UpdateWorkspaceRequest(String name, String description, String scenarioName) {
    }

    public record CreateSessionRequest(String title, BranchSpec branch) {
    }

    /**
     * 新建会话时的可选分支挂钩（2026-09-14 会话↔worktree）。
     *
     * @param type none（缺省，不挂载，行为与旧版一致）/ new（新建分支）/ existing（挂已有分支）
     * @param name 分支名；new / existing 时必填
     * @param base 基准引用；仅 new 有效，缺省 = 当前 HEAD
     */
    public record BranchSpec(String type, String name, String base) {
    }

    /** 会话删除结果；worktreeWarning 非 null 表示 worktree 清理未成功（会话已删，不阻塞） */
    public record DeleteSessionResult(String worktreeWarning) {
    }

    /** 分支清单响应：current 为当前分支（非 git 仓库等异常时为 null） */
    public record BranchesResponse(String current, java.util.List<String> branches) {
    }

    /** 会话不存在 → 404，而不是让调用方看到裸 500 */
    @ExceptionHandler(WorkspaceExceptions.SessionNotFoundException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    public Map<String, String> handleSessionNotFound(WorkspaceExceptions.SessionNotFoundException e) {
        return Map.of("error", e.getMessage());
    }

    /** 标题非法（空白）→ 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Map<String, String> handleIllegalArgument(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    public record FileEntryDto(String name, String path, boolean directory, long size, long modifiedAt) {
    }

    public record FilePreviewInfo(String name, String path, String kind, long size, boolean truncated) {
    }

    private static final Set<String> TEXT_EXT = Set.of(
            "txt", "md", "markdown", "log", "json", "yaml", "yml", "toml", "xml", "html", "htm", "css", "scss", "less",
            "js", "jsx", "ts", "tsx", "mjs", "cjs", "java", "kt", "kts", "groovy", "scala",
            "py", "pyi", "rb", "php", "go", "rs", "swift", "c", "h", "cpp", "hpp", "cs",
            "sh", "bash", "zsh", "bat", "cmd", "ps1", "psm1", "sql", "properties", "ini", "cfg", "conf",
            "gradle", "sbt", "makefile", "dockerfile", "vue", "svelte", "astro"
    );

    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "tiff");

    private static final long MAX_TEXT_SIZE = 512 * 1024;
    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024;

    @GetMapping
    public List<WorkspaceSummary> list() {
        return workspaceManager.getUserWorkspaces(com.xinl.easyclaw.config.AppConstants.DEFAULT_USER_ID);
    }

    @PostMapping
    public WorkspaceContext create(@RequestBody CreateWorkspaceRequest req) {
        String type = WorkspaceManager.normalizeType(req.type());
        String target = (req.scenarioName() == null || req.scenarioName().isBlank())
                ? DEFAULT_SCENARIO_NAME : req.scenarioName().trim();
        // 建工作区前先校验「场景存在 + 类型匹配」：不满足直接 400，避免先落库一个
        // 半成品工作区再静默回退到错误类型的场景
        assertScenarioCompatible(target, type);

        WorkspaceContext ctx = workspaceManager.createWorkspace(
                com.xinl.easyclaw.config.AppConstants.DEFAULT_USER_ID,
                req.name(), req.description(), req.path(), type);
        scenarioService.activateByName(ctx.getWorkspaceId(), target);
        return ctx;
    }

    @PutMapping("/{id}")
    public WorkspaceSummary update(@PathVariable String id, @RequestBody UpdateWorkspaceRequest req) {
        WorkspaceSummary summary = workspaceManager.updateWorkspace(id, req.name(), req.description());
        // 编辑时未传场景 = 该表单没带这个字段（老客户端），保持原绑定不动
        if (req.scenarioName() != null && !req.scenarioName().isBlank()) {
            String target = req.scenarioName().trim();
            // 工作区类型创建后不可变，切换场景只能在同类型内切换
            assertScenarioCompatible(target, summary.getType());
            scenarioService.activateByName(id, target);
        }
        return summary;
    }

    /**
     * 校验目标场景存在/启用，且其模式与工作区类型一致（single↔SOLO、team↔团队、schedule↔定时）。
     * 任一不满足抛 {@link IllegalArgumentException}（由控制器统一转 400）。
     */
    private void assertScenarioCompatible(String scenarioName, String workspaceType) {
        var scenario = scenarioService.findActiveByName(scenarioName)
                .orElseThrow(() -> new IllegalArgumentException("场景不存在或已停用: " + scenarioName));
        String mode = scenario.getMode() == null ? "single" : scenario.getMode();
        if (!mode.equals(workspaceType)) {
            throw new IllegalArgumentException(
                    "场景类型与工作区类型不匹配：工作区为 " + workspaceType
                            + "，不能绑定 " + mode + " 类型的场景「" + scenario.getDisplayName() + "」");
        }
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        workspaceManager.deleteWorkspace(id);
    }

    @GetMapping("/{id}/sessions")
    public List<SessionEntity> sessions(@PathVariable String id) {
        return sessionHistoryService.listSessions(id);
    }

    @PostMapping("/{id}/sessions")
    public SessionEntity createSession(@PathVariable String id, @RequestBody CreateSessionRequest req) {
        SessionEntity entity = new SessionEntity();
        entity.setId("session-" + System.currentTimeMillis());
        entity.setWorkspaceId(id);
        entity.setTitle(req.title() == null ? "新会话" : req.title());
        entity.setStatus("active");
        entity.setCreatedAt(java.time.Instant.now());
        entity.setLastAccessedAt(java.time.Instant.now());

        // 可选：挂 git worktree。branch 缺省或 type=none 时完全不触碰（行为与旧版一致）。
        BranchSpec spec = req.branch();
        if (spec != null && spec.type() != null && !"none".equals(spec.type())) {
            WorkspaceContext ws = workspaceManager.getWorkspace(id);
            if (ws == null) {
                throw new ApiExceptions.NotFoundException("Workspace 未找到: " + id);
            }
            if (spec.name() == null || spec.name().isBlank()) {
                throw new ApiExceptions.BadRequestException("branch.type=" + spec.type() + " 时 branch.name 必填");
            }
            WorktreeService.WorktreeResult result = switch (spec.type()) {
                case "new" -> worktreeService.create(ws.getPath(), entity.getId(), spec.name(), spec.base());
                case "existing" -> worktreeService.attach(ws.getPath(), entity.getId(), spec.name());
                default -> throw new ApiExceptions.BadRequestException(
                        "未知 branch.type: " + spec.type() + "（可选 none / new / existing）");
            };
            if (!result.ok()) {
                // 用户明确要求隔离时建不出 worktree，直接报错 —— 降级成不隔离的会话会误导
                throw new ApiExceptions.BadRequestException(result.message());
            }
            entity.setWorktreePath(result.path().toString());
            entity.setBranch(spec.name());
        }

        workspaceManager.createSession(id, entity.getId(), entity.getTitle(),
                entity.getWorktreePath(), entity.getBranch());
        return entity;
    }

    @PutMapping("/{id}/sessions/{sessionId}")
    public SessionEntity renameSession(@PathVariable String id, @PathVariable String sessionId,
                                      @RequestBody CreateSessionRequest req) {
        return sessionHistoryService.renameSession(id, sessionId, req.title());
    }

    @DeleteMapping("/{id}/sessions/{sessionId}")
    public DeleteSessionResult deleteSession(@PathVariable String id, @PathVariable String sessionId,
                                             @RequestParam(defaultValue = "keep") String worktree) {
        WorkspaceContext ws = workspaceManager.getWorkspace(id);
        if (ws != null) {
            String worktreeWarning = null;
            // worktree=remove/force 时先清 worktree；失败不阻塞删会话（Windows 文件占用是常态），
            // 警告带回给前端提示用户手动清理。目录不存在时 remove 幂等 ok，无 worktree 会话传 remove 无害。
            if ("remove".equals(worktree) || "force".equals(worktree)) {
                WorktreeService.WorktreeResult r =
                        worktreeService.remove(ws.getPath(), sessionId, "force".equals(worktree));
                if (!r.ok()) {
                    worktreeWarning = r.message();
                    log.warn("会话 {} 的 worktree 清理失败（会话照常删除）: {}", sessionId, r.message());
                }
            }
            sessionHistoryService.deleteSession(ws, sessionId);
            // 会话被删除是明确的终止意图：强制驱逐内存状态（订阅、工具授权、计数器），
            // 否则 sessionId 若被复用会继承旧的 turnAllowed 授权而绕过确认弹窗
            agentService.releaseSession(sessionId);
            return new DeleteSessionResult(worktreeWarning);
        }
        return new DeleteSessionResult(null);
    }

    /** 列出工作区 git 分支（新建会话表单的分支下拉数据源）；非 git 仓库时 current 为 null、branches 为空 */
    @GetMapping("/{id}/branches")
    public BranchesResponse branches(@PathVariable String id) {
        WorkspaceContext ws = workspaceManager.getWorkspace(id);
        if (ws == null) {
            throw new ApiExceptions.NotFoundException("Workspace 未找到: " + id);
        }
        return new BranchesResponse(worktreeService.currentBranch(ws.getPath()),
                worktreeService.listBranches(ws.getPath()));
    }

    @GetMapping("/{id}/permissions")
    public List<PermissionRuleEntity> permissions(@PathVariable String id) {
        return permissionRuleService.findForWorkspace(id);
    }

    @PostMapping("/{id}/permissions/{toolName}")
    public PermissionRuleEntity addPermission(@PathVariable String id, @PathVariable String toolName) {
        agentService.allowPermanently(id, List.of(toolName));
        return permissionRuleService.findForWorkspace(id).stream()
                .filter(r -> toolName.equals(r.getToolName()))
                .findFirst()
                .orElse(new PermissionRuleEntity());
    }

    @DeleteMapping("/{id}/permissions/{toolName}")
    public void revokePermission(@PathVariable String id, @PathVariable String toolName) {
        agentService.revokePermanently(id, toolName);
    }

    @GetMapping("/{id}/files")
    public List<FileEntryDto> files(@PathVariable String id, @RequestParam(required = false) String path) {
        WorkspaceContext ws = workspaceManager.getWorkspace(id);
        if (ws == null) {
            return List.of();
        }
        Path root = ws.getPath();
        Path dir = root;
        if (path != null && !path.isBlank()) {
            Path candidate = root.resolve(path).normalize();
            if (candidate.startsWith(root)) {
                dir = candidate;
            }
        }
        List<FileEntryDto> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (var stream = Files.list(dir)) {
            stream.sorted(Comparator.comparing(p -> p.getFileName().toString())).forEach(p -> {
                String name = p.getFileName().toString();
                if (name.equals(".easyClaw") || name.equals(".git") || name.equals(".idea")
                        || name.equals(".vscode") || name.equals(".env") || name.equals("node_modules")
                        || name.endsWith(".tmp")) {
                    return;
                }
                boolean isDir = Files.isDirectory(p);
                long size = 0;
                if (!isDir) {
                    try {
                        size = Files.size(p);
                    } catch (IOException ignored) {
                    }
                }
                String rel = root.relativize(p).toString().replace('\\', '/');
                result.add(new FileEntryDto(name, rel, isDir, size,
                        p.toFile().lastModified()));
            });
        } catch (IOException ignored) {
        }
        return result;
    }

    /**
     * 文件元信息：返回 kind (text/image/binary) 和 size，前端据此决定是否展示"打开"按钮
     */
    @GetMapping("/{id}/file-info")
    public ResponseEntity<FilePreviewInfo> fileInfo(@PathVariable String id, @RequestParam String path) {
        WorkspaceContext ws = workspaceManager.getWorkspace(id);
        if (ws == null) return ResponseEntity.notFound().build();
        Path target = ws.getPath().resolve(path).normalize();
        if (!target.startsWith(ws.getPath()) || !Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }
        String name = target.getFileName().toString();
        String ext = extOf(name);
        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
        String kind;
        if (IMAGE_EXT.contains(ext)) {
            kind = size <= MAX_IMAGE_SIZE ? "image" : "binary";
        } else if (TEXT_EXT.contains(ext) || size <= 4096) {
            kind = "text";
        } else {
            kind = "binary";
        }
        return ResponseEntity.ok(new FilePreviewInfo(name, path, kind, size, size > MAX_TEXT_SIZE));
    }

    /**
     * 文件内容：文本返回纯文本 + charset，图片返回二进制 + Content-Type
     * 前端直接用 fetch 或 <img src> 渲染
     */
    @GetMapping(value = "/{id}/file-content", produces = "*/*")
    public ResponseEntity<byte[]> fileContent(@PathVariable String id, @RequestParam String path) {
        WorkspaceContext ws = workspaceManager.getWorkspace(id);
        if (ws == null) return ResponseEntity.notFound().build();
        Path target = ws.getPath().resolve(path).normalize();
        if (!target.startsWith(ws.getPath()) || !Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }
        String name = target.getFileName().toString();
        String ext = extOf(name);
        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }

        try {
            if (IMAGE_EXT.contains(ext)) {
                if (size > MAX_IMAGE_SIZE) return ResponseEntity.status(413).build();
                byte[] bytes = Files.readAllBytes(target);
                String mime = guessMime(ext);
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(mime))
                        .header(HttpHeaders.CACHE_CONTROL, "max-age=600")
                        .body(bytes);
            }

            boolean isText = TEXT_EXT.contains(ext) || size <= 4096;
            if (isText) {
                byte[] bytes;
                boolean truncated = false;
                if (size > MAX_TEXT_SIZE) {
                    byte[] full = Files.readAllBytes(target);
                    bytes = Arrays.copyOf(full, (int) MAX_TEXT_SIZE);
                    truncated = true;
                } else {
                    bytes = Files.readAllBytes(target);
                }
                MediaType mediaType = MediaType.parseMediaType("text/plain;charset=UTF-8");
                return ResponseEntity.ok()
                        .contentType(mediaType)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                        .header("X-File-Truncated", String.valueOf(truncated))
                        .body(bytes);
            }

            return ResponseEntity.status(415).build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private static String guessMime(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            case "ico" -> "image/x-icon";
            case "tiff" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    /**
     * 刷新运行环境：启动新 cmd 进程读取系统最新 PATH，
     * 存入 workspaceManager 并重建 HarnessAgent，让后续 shell 执行使用新 PATH。
     */
    @PostMapping("/{workspaceId}/refresh-env")
    public ResponseEntity<Map<String, Object>> refreshEnv(@PathVariable String workspaceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "echo %PATH%");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

            if (output != null && !output.isBlank()) {
                agentBuilder.setRefreshedPath(workspaceId, output.trim());
                workspaceManager.rebuildAgent(workspaceId, null);
                result.put("success", true);
                result.put("pathPreview", output.length() > 200 ? output.substring(0, 200) + "..." : output);
                result.put("pathSegments", output.split(";").length);
            } else {
                result.put("success", false);
                result.put("error", "无法读取系统 PATH");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /** 查询当前 workspace 生效的 PATH（调试用）。 */
    @GetMapping("/{workspaceId}/cached-path")
    public ResponseEntity<Map<String, Object>> getCachedPath(@PathVariable String workspaceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        String path = agentBuilder.getRefreshedPath(workspaceId);
        if (path == null) {
            path = System.getenv("PATH");
        }
        result.put("path", path);
        result.put("segments", path != null ? path.split(";").length : 0);
        return ResponseEntity.ok(result);
    }
}
