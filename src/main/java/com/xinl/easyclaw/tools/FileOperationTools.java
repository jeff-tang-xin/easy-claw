package com.xinl.easyclaw.tools;

import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceSandbox;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 文件操作工具（仅提供框架 FilesystemTool 不覆盖的补充能力）
 * <p>
 * AgentScope 内置的 FilesystemTool 已提供 read_file / write_file / edit_file / grep_files /
 * glob_files / list_files，且支持 offset/limit 等参数。本工具仅补充框架没有的能力：
 * list_directory（带 emoji 图标）和 search_files（按文件名关键词搜索）。
 * <p>
 * 所有路径均相对于当前 Workspace 根目录解析，由 {@link WorkspaceSandbox} 保证
 * 不越界、不访问敏感目录。WorkspaceContext 由 RuntimeContext 自动注入，不暴露给 LLM。
 */
@Component
public class FileOperationTools {

    private static final Logger log = LoggerFactory.getLogger(FileOperationTools.class);

    private final WorkspaceSandbox sandbox;

    public FileOperationTools(WorkspaceSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Tool(name = "list_directory", description = "列出当前工作区内指定目录下的所有文件和子目录（带图标区分）")
    public String listDirectory(@ToolParam(name = "path", description = "目录路径（相对当前工作区根目录，留空表示根目录）") String path,
                                WorkspaceContext workspace) {
        try {
            Path resolved = sandbox.resolvePath(workspace, path == null || path.isBlank() ? "." : path);
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

    @Tool(name = "search_files", description = "在当前工作区内按文件名关键词搜索文件，返回匹配的路径列表")
    public String searchFiles(@ToolParam(name = "keyword", description = "搜索关键词（文件名包含）") String keyword,
                              WorkspaceContext workspace) {
        try {
            Path base = workspace.getPath().toAbsolutePath().normalize();
            StringBuilder sb = new StringBuilder();
            try (Stream<Path> walker = Files.walk(base)) {
                walker.limit(10_000)
                        .filter(p -> !p.equals(base))
                        .filter(p -> p.getFileName() != null
                                && p.getFileName().toString().toLowerCase().contains(keyword.toLowerCase()))
                        .filter(p -> sandbox.validatePath(workspace, base.relativize(p).toString()))
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
}
