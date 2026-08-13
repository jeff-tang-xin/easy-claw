package com.xinl.easyclaw.tools;

import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Component
public class FileOperationTools {

    private static final Logger log = LoggerFactory.getLogger(FileOperationTools.class);

    private final WorkspaceSandbox sandbox;

    public FileOperationTools(WorkspaceSandbox sandbox) {
        this.sandbox = sandbox;
    }

    public String listDirectory(
            String path) {
        try {
            WorkspaceContext workspace = sandbox.defaultWorkspace();
            Path resolved = sandbox.resolvePath(workspace, (path == null || path.isBlank()) ? "." : path);
            if (!Files.exists(resolved) || !Files.isDirectory(resolved)) {
                return "❌ 目录不存在: " + path;
            }
            StringBuilder sb = new StringBuilder();
            try (Stream<Path> stream = Files.list(resolved)) {
                stream.sorted().forEach(p -> {
                    String name = p.getFileName().toString();
                    sb.append(Files.isDirectory(p) ? "📁 " + name + "/" : "📄 " + name).append("\n");
                });
            }
            return sb.toString();
        } catch (SecurityException e) {
            return "❌ " + e.getMessage();
        } catch (Exception e) {
            log.error("列出目录失败: {}", path, e);
            return "❌ 列出目录失败: " + e.getMessage();
        }
    }

    public String searchFiles(
            String keyword) {
        try {
            WorkspaceContext workspace = sandbox.defaultWorkspace();
            Path base = workspace.getPath().toAbsolutePath().normalize();
            StringBuilder sb = new StringBuilder();
            try (Stream<Path> walker = Files.walk(base)) {
                walker.limit(10_000)
                        .filter(p -> !p.equals(base))
                        .filter(p -> p.getFileName() != null
                                && p.getFileName().toString().toLowerCase().contains(keyword.toLowerCase()))
                        .limit(50)
                        .forEach(p -> sb.append(base.relativize(p)).append("\n"));
            }
            return sb.isEmpty() ? "未找到文件名包含 \"" + keyword + "\" 的文件" : sb.toString();
        } catch (SecurityException e) {
            return "❌ " + e.getMessage();
        } catch (Exception e) {
            log.error("搜索文件失败: {}", e.getMessage());
            return "❌ 搜索文件失败: " + e.getMessage();
        }
    }

    public String readFile(
            String filePath) {
        try {
            WorkspaceContext workspace = sandbox.defaultWorkspace();
            Path resolved = sandbox.resolvePath(workspace, filePath);
            if (!Files.exists(resolved)) {
                return "❌ 文件不存在: " + filePath;
            }
            if (Files.size(resolved) > 1_000_000) {
                return "⚠️ 文件较大（>1MB），建议先搜索关键片段";
            }
            return Files.readString(resolved);
        } catch (SecurityException e) {
            return "❌ " + e.getMessage();
        } catch (Exception e) {
            log.error("读取文件失败: {}", filePath, e);
            return "❌ 读取文件失败: " + e.getMessage();
        }
    }

    public String writeFile(
            String filePath,
            String content) {
        try {
            WorkspaceContext workspace = sandbox.defaultWorkspace();
            Path resolved = sandbox.resolvePath(workspace, filePath);
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content);
            return "✅ 已写入 " + filePath + " (" + content.length() + " 字符)";
        } catch (SecurityException e) {
            return "❌ " + e.getMessage();
        } catch (Exception e) {
            log.error("写入文件失败: {}", filePath, e);
            return "❌ 写入文件失败: " + e.getMessage();
        }
    }
}
