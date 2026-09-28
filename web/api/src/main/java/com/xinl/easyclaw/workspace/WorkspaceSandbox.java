package com.xinl.easyclaw.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

    public WorkspaceSandbox(
            @Value("${ai.workspace.security.forbidden-paths:}") List<String> forbiddenPaths) {
        this.forbiddenPaths = forbiddenPaths == null ? List.of() : forbiddenPaths;
    }

    public boolean validatePath(WorkspaceContext workspace, String requestedPath) {
        try {
            resolveStrict(workspace, requestedPath);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * 解析并校验用户提供的路径（必须位于 Workspace 内且非敏感目录）
     */
    public Path resolvePath(WorkspaceContext workspace, String userPath) {
        return resolveStrict(workspace, userPath);
    }

    /**
     * 唯一的路径校验实现：词法校验 + 符号链接解析校验 + 敏感目录校验
     * <p>
     * 单纯的 {@code normalize()} 只做词法处理，不解析符号链接，
     * 工作区内指向外部的 symlink（如 {@code link -> C:\Windows}）会导致沙箱逃逸。
     * 因此这里额外对「最近的已存在祖先」调用 {@code toRealPath()} 做真实路径比对，
     * 以兼容目标路径尚不存在（写新文件）的场景。
     */
    private Path resolveStrict(WorkspaceContext workspace, String userPath) {
        if (workspace == null || workspace.getPath() == null) {
            throw new SecurityException("当前调用缺少 Workspace 上下文，无法执行文件操作");
        }
        Path base = workspace.getPath().toAbsolutePath().normalize();
        Path target = base.resolve(userPath).normalize();

        // 1. 词法校验
        if (!target.startsWith(base)) {
            throw new SecurityException(
                    "路径越界: " + userPath + " 超出工作区 " + workspace.getPath());
        }

        // 2. 符号链接校验：逐级向上找到最近的已存在祖先，比对真实路径
        Path probe = target;
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        if (probe != null) {
            try {
                Path realBase = base.toRealPath();
                Path realProbe = probe.toRealPath();
                if (!realProbe.startsWith(realBase)) {
                    throw new SecurityException("路径越界（符号链接解析后）: " + userPath);
                }
            } catch (IOException e) {
                // 校验不确定时一律拒绝，遵循安全默认
                throw new SecurityException("路径校验失败，无法解析真实路径: " + userPath, e);
            }
        }

        // 3. 敏感目录校验
        if (isForbidden(target)) {
            throw new SecurityException("禁止访问系统/敏感目录: " + userPath);
        }
        return target;
    }

    public void checkSystemDirectory(WorkspaceContext workspace, String path) {
        Path targetPath = resolveStrict(workspace, path);
        // 按路径段精确比较，避免 my.easyClawBackup / notes.easyClaw.md 这类普通文件被误拒
        for (Path part : targetPath) {
            String name = part.getFileName() == null ? "" : part.getFileName().toString();
            if (".easyClaw".equals(name)) {
                throw new SecurityException("禁止修改系统目录: .easyClaw");
            }
        }
    }

    public List<Path> listFiles(WorkspaceContext workspace, String relativePath) {
        Path targetPath = resolveStrict(workspace, relativePath);

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
