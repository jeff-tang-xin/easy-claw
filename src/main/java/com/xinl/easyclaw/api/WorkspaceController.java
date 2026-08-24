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

    private final WorkspaceManager workspaceManager;
    private final SessionHistoryService sessionHistoryService;
    private final AgentService agentService;
    private final WorkspaceSandbox sandbox;

    public WorkspaceController(WorkspaceManager workspaceManager,
                               SessionHistoryService sessionHistoryService,
                               AgentService agentService,
                               WorkspaceSandbox sandbox) {
        this.workspaceManager = workspaceManager;
        this.sessionHistoryService = sessionHistoryService;
        this.agentService = agentService;
        this.sandbox = sandbox;
    }

    public record CreateWorkspaceRequest(String name, String description, String path) {
    }

    public record UpdateWorkspaceRequest(String name, String description) {
    }

    public record CreateSessionRequest(String title) {
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
        return workspaceManager.createWorkspace(
                com.xinl.easyclaw.config.AppConstants.DEFAULT_USER_ID,
                req.name(), req.description(), req.path());
    }

    @PutMapping("/{id}")
    public WorkspaceSummary update(@PathVariable String id, @RequestBody UpdateWorkspaceRequest req) {
        return workspaceManager.updateWorkspace(id, req.name(), req.description());
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

    @DeleteMapping("/{id}/sessions/{sessionId}")
    public void deleteSession(@PathVariable String id, @PathVariable String sessionId) {
        WorkspaceContext ws = workspaceManager.getWorkspace(id);
        if (ws != null) {
            sessionHistoryService.deleteSession(ws, sessionId);
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
                workspaceManager.setRefreshedPath(workspaceId, output.trim());
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
        String path = workspaceManager.getRefreshedPath(workspaceId);
        if (path == null) {
            path = System.getenv("PATH");
        }
        result.put("path", path);
        result.put("segments", path != null ? path.split(";").length : 0);
        return ResponseEntity.ok(result);
    }
}
