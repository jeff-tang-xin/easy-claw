package com.xinl.easyclaw.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Workspace 文件沙箱
 * <p>
 * 所有 Agent 工具的文件操作必须经过本沙箱验证：
 * 路径必须解析在 Workspace 目录内，且不得访问系统/敏感目录。
 */
@Component
public class WorkspaceSandbox {

    private final List<String> forbiddenPaths;
    private final String workspaceRoot;

    public WorkspaceSandbox(
            @Value("${ai.workspace.security.forbidden-paths:}") List<String> forbiddenPaths,
            @Value("${easy-claw.workspace.root:${user.home}/.easyClaw/workspaces}") String workspaceRoot) {
        this.forbiddenPaths = forbiddenPaths == null ? List.of() : forbiddenPaths;
        this.workspaceRoot = workspaceRoot;
    }

    public WorkspaceContext defaultWorkspace() {
        return WorkspaceContext.builder()
                .workspaceId("default")
                .name("Default Workspace")
                .path(Path.of(workspaceRoot))
                .build();
    }

    public boolean validatePath(WorkspaceContext workspace, String requestedPath) {
        Path basePath = workspace.getPath().toAbsolutePath().normalize();
        Path targetPath = basePath.resolve(requestedPath).normalize();
        return targetPath.startsWith(basePath) && !isForbidden(targetPath);
    }

    /**
     * 解析并校验用户提供的路径（必须位于 Workspace 内且非敏感目录）
     */
    public Path resolvePath(WorkspaceContext workspace, String userPath) {
        if (workspace == null || workspace.getPath() == null) {
            throw new SecurityException("当前调用缺少 Workspace 上下文，无法执行文件操作");
        }
        Path basePath = workspace.getPath().toAbsolutePath().normalize();
        Path targetPath = basePath.resolve(userPath).normalize();

        if (!targetPath.startsWith(basePath)) {
            throw new SecurityException(
                    "路径越界: " + userPath + " 超出工作区 " + workspace.getPath());
        }
        if (isForbidden(targetPath)) {
            throw new SecurityException("禁止访问系统/敏感目录: " + userPath);
        }
        return targetPath;
    }

    public void checkSystemDirectory(WorkspaceContext workspace, String path) {
        Path targetPath = resolvePath(workspace, path);
        if (targetPath.toString().contains(".easyClaw")) {
            throw new SecurityException("禁止修改系统目录: .easyClaw");
        }
    }

    public List<Path> listFiles(WorkspaceContext workspace, String relativePath) {
        Path basePath = workspace.getPath().toAbsolutePath().normalize();
        Path targetPath = basePath.resolve(relativePath).normalize();

        if (!targetPath.startsWith(basePath)) {
            throw new SecurityException("路径超出工作区范围");
        }
        if (isForbidden(targetPath)) {
            throw new SecurityException("禁止访问系统/敏感目录: " + relativePath);
        }

        try {
            return Files.list(targetPath)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("无法列出目录: " + relativePath, e);
        }
    }

    private boolean isForbidden(Path targetPath) {
        for (Path part : targetPath) {
            String name = part.getFileName() == null ? "" : part.getFileName().toString();
            if (forbiddenPaths.contains(name)) {
                return true;
            }
        }
        return false;
    }
}
