package com.xinl.easyclaw.skill.service;

import com.xinl.easyclaw.config.SystemHomePaths;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Skill 资源解析器：统一处理全局/工作区两级 Skill 的查找、列表与读取。
 *
 * <p>查找优先级：工作区目录 Skill &gt; 工作区单文件 Skill &gt; 全局目录 Skill &gt; 全局单文件 Skill。</p>
 */
@Component
public class SkillResolver {

    private static final Logger log = LoggerFactory.getLogger(SkillResolver.class);

    private final WorkspaceManager workspaceManager;

    public SkillResolver(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    /**
     * 按名称解析 Skill 根目录或单文件所在目录。
     */
    public Path resolveSkillDir(String workspaceId, String skillId) {
        Path skillFile = resolveSkillFile(workspaceId, skillId);
        return skillFile == null ? null : skillFile.getParent();
    }

    /**
     * 按名称解析 Skill 的实际 Markdown 文件路径（目录型返回 SKILL.md，单文件型返回 xxx.md）。
     */
    public Path resolveSkillFile(String workspaceId, String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return null;
        }

        // 1. 工作区目录 Skill / 单文件 Skill
        Path wsRoot = workspaceSkillRoot(workspaceId);
        if (wsRoot != null) {
            Path wsDir = wsRoot.resolve(skillId);
            if (Files.isDirectory(wsDir) && Files.isRegularFile(wsDir.resolve("SKILL.md"))) {
                return wsDir.resolve("SKILL.md");
            }
            Path wsFile = wsRoot.resolve(skillId + ".md");
            if (Files.isRegularFile(wsFile)) {
                return wsFile;
            }
        }

        // 2. 全局目录 Skill / 单文件 Skill
        Path globalRoot = SystemHomePaths.globalSkillsDir();
        Path globalDir = globalRoot.resolve(skillId);
        if (Files.isDirectory(globalDir) && Files.isRegularFile(globalDir.resolve("SKILL.md"))) {
            return globalDir.resolve("SKILL.md");
        }
        Path globalFile = globalRoot.resolve(skillId + ".md");
        if (Files.isRegularFile(globalFile)) {
            return globalFile;
        }

        return null;
    }

    /**
     * 列出工作区 + 全局所有 Skill 名称（去重，工作区优先）。
     */
    public List<String> listSkillNames(String workspaceId) {
        Set<String> names = new LinkedHashSet<>();
        Path wsRoot = workspaceSkillRoot(workspaceId);
        if (wsRoot != null) {
            collectSkillNames(wsRoot, names);
        }
        collectSkillNames(SystemHomePaths.globalSkillsDir(), names);
        return List.copyOf(names);
    }

    /**
     * 列出指定 Skill 下的所有可用子文件（相对路径，含 SKILL.md）。
     */
    public List<String> listAvailableFiles(Path skillDir) {
        if (skillDir == null || !Files.isDirectory(skillDir)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        try (var walker = Files.walk(skillDir)) {
            walker.filter(Files::isRegularFile)
                    .forEach(f -> result.add(skillDir.relativize(f).toString().replace('\\', '/')));
        } catch (Exception ignored) {
            // 忽略扫描异常
        }
        Collections.sort(result);
        return result;
    }

    /**
     * 读取 Skill 资源内容，并去除 SKILL.md 的 frontmatter。
     */
    public String readSkillResource(String workspaceId, String skillId, String relativePath) {
        Path skillDir = resolveSkillDir(workspaceId, skillId);
        if (skillDir == null) {
            return null;
        }
        String rel = relativePath == null || relativePath.isBlank() ? "SKILL.md" : relativePath;
        Path target = skillDir.resolve(rel).normalize();
        if (!target.startsWith(skillDir)) {
            return null;
        }
        // 单文件 Skill 兼容：默认 SKILL.md 不存在时回退到 {skillId}.md
        if (!Files.isRegularFile(target) && "SKILL.md".equals(rel)) {
            Path singleFile = skillDir.resolve(skillId + ".md");
            if (Files.isRegularFile(singleFile)) {
                target = singleFile;
            }
        }
        if (!Files.isRegularFile(target)) {
            return null;
        }
        try {
            String content = Files.readString(target, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF?", "");
            if (target.getFileName().toString().equals("SKILL.md") && content.startsWith("---")) {
                int end = content.indexOf("---", 3);
                if (end > 0) {
                    content = content.substring(end + 3).trim();
                }
            }
            return content;
        } catch (IOException e) {
            log.warn("读取 Skill 资源失败: path={}, err={}", target, e.getMessage());
            return null;
        }
    }

    /**
     * 生成给 LLM 看的 Skill 清单文本。
     */
    public String scanAllSkills(String workspaceId) {
        StringBuilder sb = new StringBuilder("# 可用 Skills\n\n");
        for (String skillName : listSkillNames(workspaceId)) {
            Path skillFile = resolveSkillFile(workspaceId, skillName);
            if (skillFile == null || !Files.isRegularFile(skillFile)) {
                continue;
            }
            boolean directorySkill = "SKILL.md".equals(skillFile.getFileName().toString());
            String desc = extractDescription(skillFile);
            sb.append("## ").append(skillName).append("\n");
            if (!desc.isBlank()) {
                sb.append(desc).append("\n");
            }
            if (directorySkill) {
                List<String> subFiles = listSubFiles(skillFile.getParent());
                if (!subFiles.isEmpty()) {
                    sb.append("**可用资源：**\n");
                    for (String f : subFiles) {
                        sb.append("- `").append(f).append("`\n");
                    }
                }
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public String extractDescription(Path skillMd) {
        if (skillMd == null || !Files.isRegularFile(skillMd)) {
            return "";
        }
        try {
            String content = Files.readString(skillMd, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF?", "");
            if (!content.startsWith("---")) {
                return "";
            }
            int end = content.indexOf("---", 3);
            if (end < 0) {
                return "";
            }
            String frontmatter = content.substring(3, end).trim();
            for (String line : frontmatter.split("\n")) {
                String t = line.trim();
                if (t.startsWith("description:")) {
                    return t.substring("description:".length()).trim().replaceAll("^[\"']|[\"']$", "");
                }
            }
        } catch (Exception ignored) {
            // 忽略解析异常
        }
        return "";
    }

    private List<String> listSubFiles(Path skillRoot) {
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(skillRoot)) {
            return result;
        }
        try (var walker = Files.walk(skillRoot)) {
            walker.filter(Files::isRegularFile)
                    .forEach(f -> {
                        String rel = skillRoot.relativize(f).toString().replace('\\', '/');
                        if (!"SKILL.md".equals(rel) && rel.endsWith(".md")) {
                            result.add(rel);
                        }
                    });
        } catch (Exception ignored) {
            // 忽略扫描异常
        }
        Collections.sort(result);
        return result;
    }

    private Path workspaceSkillRoot(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return null;
        }
        WorkspaceContext ws = workspaceManager.getWorkspace(workspaceId);
        if (ws == null) {
            return null;
        }
        Path dir = ws.getPath().resolve(".easyClaw/agent/skills");
        return Files.isDirectory(dir) ? dir : null;
    }

    private void collectSkillNames(Path root, Set<String> out) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.list(root)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p) && Files.isRegularFile(p.resolve("SKILL.md"))) {
                    out.add(name);
                } else if (name.endsWith(".md")) {
                    out.add(name.substring(0, name.length() - 3));
                }
            });
        } catch (IOException ignored) {
            // 忽略目录读取异常
        }
    }
}
