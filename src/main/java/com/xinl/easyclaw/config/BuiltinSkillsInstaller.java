package com.xinl.easyclaw.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 启动时将 resources/skills 下的内置 Skill 复制到 ~/.easyClaw/skills/。
 * <p>策略：
 * <ul>
 *   <li>每次启动都会扫描 resources，确保新增的内置 Skill 文件被复制（版本升级时生效）</li>
 *   <li>已存在的文件不覆盖（用户可能改过）—— 幂等安全</li>
 *   <li>用户自建的 global/workspace Skill 完全不受影响</li>
 * </ul>
 */
@Slf4j
@Component
public class BuiltinSkillsInstaller implements ApplicationRunner {

    private static final String RESOURCE_BASE = "classpath:/skills/";

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path targetDir = SystemHomePaths.globalSkillsDir();
        Files.createDirectories(targetDir);

        // 迁移旧版 _index.md → SKILL.md（harness 规范）
        migrateLegacyIndexFiles(targetDir);

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        // 所有内置 Skill 统一为目录形式：resources/skills/**/SKILL.md + 子文件
        copyResources(resolver, RESOURCE_BASE + "**/*.md", targetDir, "/skills/");
    }

    /**
     * 兼容旧版：
     * <ol>
     *   <li>把 _index.md 重命名为 SKILL.md（harness 规范）</li>
     *   <li>给缺少 name 字段的 SKILL.md 补上 name（harness 要求 name + description）</li>
     * </ol>
     */
    private void migrateLegacyIndexFiles(Path targetDir) throws IOException {
        if (!Files.isDirectory(targetDir)) return;
        try (var stream = Files.list(targetDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                Path oldIndex = dir.resolve("_index.md");
                Path newIndex = dir.resolve("SKILL.md");
                try {
                    if (Files.exists(oldIndex) && !Files.exists(newIndex)) {
                        Files.move(oldIndex, newIndex);
                        log.info("已迁移旧版 _index.md → SKILL.md: {}", dir.getFileName());
                    } else if (Files.exists(oldIndex) && Files.exists(newIndex)) {
                        Files.delete(oldIndex);
                        log.info("已清理旧版 _index.md（SKILL.md 已存在）: {}", dir.getFileName());
                    }
                    ensureSkillHasName(newIndex, dir.getFileName().toString());
                } catch (IOException e) {
                    log.warn("迁移 skill 失败: {} - {}", dir.getFileName(), e.getMessage());
                }
            });
        }
    }

    /**
     * harness 要求 SKILL.md frontmatter 必须有 name + description。
     * 如果缺 name，就用目录名补上。
     */
    private void ensureSkillHasName(Path skillMd, String dirName) throws IOException {
        if (!Files.exists(skillMd)) return;
        String content = Files.readString(skillMd, StandardCharsets.UTF_8);
        if (content.contains("name:")) return;
        // 兼容 UTF-8 BOM：\uFEFF 可能在文件开头
        String updated = content.replaceFirst("^\\uFEFF?", "");
        updated = updated.replaceFirst("(?s)^---\\s*\\r?\\n", "---\nname: " + dirName + "\n");
        if (!updated.equals(content)) {
            Files.writeString(skillMd, updated, StandardCharsets.UTF_8);
            log.info("已补上 name 字段: {}", skillMd);
        }
    }

    /**
     * 复制 resources 下匹配 pattern 的 md 文件到 targetDir。
     * <p>已存在的文件跳过，只复制新增的。</p>
     *
     * @param pathMarker 用于从资源 URL 中提取相对路径的标记，例如 "/skills/" 或 ""（空表示直接取文件名）
     */
    private void copyResources(PathMatchingResourcePatternResolver resolver, String pattern,
                               Path targetDir, String pathMarker) throws IOException {
        Resource[] resources = resolver.getResources(pattern);
        for (Resource res : resources) {
            String urlPath = res.getURL().getPath();
            int idx = urlPath.indexOf(pathMarker);
            if (idx < 0) continue;
            String relative = urlPath.substring(idx + pathMarker.length());
            Path target = targetDir.resolve(relative);

            Files.createDirectories(target.getParent());

            if (Files.exists(target)) {
                log.trace("内置 Skill 已存在，跳过: {}", target.getFileName());
                continue;
            }

            try (InputStream in = res.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("已安装内置 Skill: {}", target);
            } catch (IOException e) {
                log.warn("复制内置 Skill 失败: {} - {}", target, e.getMessage());
            }
        }
    }
}
