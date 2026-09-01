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

    public WorkspaceController(WorkspaceManager workspaceManager,
                               SessionHistoryService sessionHistoryService,
                               AgentService agentService,
                               WorkspaceSandbox sandbox,
                               WorkspaceAgentBuilder agentBuilder,
                               com.xinl.easyclaw.scenario.service.ScenarioService scenarioService) {
        this.workspaceManager = workspaceManager;
        this.sessionHistoryService = sessionHistoryService;
        this.agentService = agentService;
        this.sandbox = sandbox;
        this.agentBuilder = agentBuilder;
        this.scenarioService = scenarioService;
    }

    /**
     * @param scenarioName 场景标识名；前端为必填项，缺省时回退内置「通用编程」，
     *                     保证任何工作区创建后都处于明确的场景约束下
     */
    public record CreateWorkspaceRequest(String name, String description, String path, String scenarioName) {
    }

    public record UpdateWorkspaceRequest(String name, String description, String scenarioName) {
    }

    public record CreateSessionRequest(String title) {
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
        WorkspaceContext ctx = workspaceManager.createWorkspace(
                com.xinl.easyclaw.config.AppConstants.DEFAULT_USER_ID,
                req.name(), req.description(), req.path());
        bindScenario(ctx.getWorkspaceId(), req.scenarioName());
        return ctx;
    }

    @PutMapping("/{id}")
    public WorkspaceSummary update(@PathVariable String id, @RequestBody UpdateWorkspaceRequest req) {
        WorkspaceSummary summary = workspaceManager.updateWorkspace(id, req.name(), req.description());
        // 编辑时未传场景 = 该表单没带这个字段（老客户端），保持原绑定不动
        if (req.scenarioName() != null && !req.scenarioName().isBlank()) {
            bindScenario(id, req.scenarioName());
        }
        return summary;
    }

    /**
     * 绑定工作区场景。场景是「能做什么、怎么做」的约束来源，缺省一律回退
     * 内置「通用编程」，避免出现无场景的裸工作区。
     * <p>
     * 绑定失败不影响工作区本身 —— 工作区已创建成功，此时抛错会让前端以为
     * 整体失败并重试，反而产生重复工作区。
     */
    private void bindScenario(String workspaceId, String scenarioName) {
        String target = (scenarioName == null || scenarioName.isBlank())
                ? DEFAULT_SCENARIO_NAME : scenarioName.trim();
        try {
            if (scenarioService.activateByName(workspaceId, target).isEmpty()
                    && !DEFAULT_SCENARIO_NAME.equals(target)) {
                log.warn("场景[{}] 不存在或已停用，回退默认场景: workspace={}", target, workspaceId);
                scenarioService.activateByName(workspaceId, DEFAULT_SCENARIO_NAME);
            }
        } catch (Exception e) {
            log.warn("绑定场景失败（工作区已创建，可稍后手动切换）: workspace={}, scenario={}, {}",
                    workspaceId, target, e.getMessage());
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
        workspaceManager.createSession(id, entity.getId(), entity.getTitle());
        return entity;
    }

    @PutMapping("/{id}/sessions/{sessionId}")
    public SessionEntity renameSession(@PathVariable String id, @PathVariable String sessionId,
                                      @RequestBody CreateSessionRequest req) {
        return sessionHistoryService.renameSession(id, sessionId, req.title());
    }

    @DeleteMapping("/{id}/sessions/{sessionId}")
    public void deleteSession(@PathVariable String id, @PathVariable String sessionId) {
        WorkspaceContext ws = workspaceManager.getWorkspace(id);
        if (ws != null) {
            sessionHistoryService.deleteSession(ws, sessionId);
            // 会话被删除是明确的终止意图：强制驱逐内存状态（订阅、工具授权、计数器），
            // 否则 sessionId 若被复用会继承旧的 turnAllowed 授权而绕过确认弹窗
            agentService.releaseSession(sessionId);
        }
    }

    @GetMapping("/{id}/permissions")
    public List<PermissionRuleEntity> permissions(@PathVariable String id) {
        return agentService.permanentRules(id);
    }

    @PostMapping("/{id}/permissions/{toolName}")
    public PermissionRuleEntity addPermission(@PathVariable String id, @PathVariable String toolName) {
        agentService.allowPermanently(id, List.of(toolName));
        return agentService.permanentRules(id).stream()
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
