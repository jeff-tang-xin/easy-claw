package com.xinl.easyclaw.agent.embabel;

import com.xinl.easyclaw.agent.embabel.domain.SkillContextData;
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
import java.util.*;

/**
 * Skill 内容加载器 + Agent 默认 Skill 配置。
 * <p>
 * 每个 Agent 有自己的默认 Skill 集，与用户指定 Skill 合并后注入 system prompt。
 * 查找优先级（同名 skill 工作区覆盖全局）：
 * <ol>
 *   <li>{workspace}/.easyClaw/agent/skills/{skillName}/SKILL.md</li>
 *   <li>{workspace}/.easyClaw/agent/skills/{skillName}.md</li>
 *   <li>~/.easyClaw/skills/{skillName}/SKILL.md</li>
 *   <li>~/.easyClaw/skills/{skillName}.md</li>
 * </ol>
 */
@Component
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    /** Agent → 默认 Skill 名称列表（与 agent 注册名小写对应） */
    private static final Map<String, List<String>> AGENT_DEFAULT_SKILLS = Map.of(
            "orchestrator", List.of("karpathy-guidelines", "cursor-rules"),
            "coding-agent", List.of("code-refactor", "backend-architecture"),
            "file-agent", List.of("cursor-rules"),
            "web-agent", List.of("vercel-react-best-practices", "frontend-quality")
    );

    private final WorkspaceManager workspaceManager;

    public SkillLoader(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    /**
     * 加载指定 skill 的完整 Markdown 内容（去掉 frontmatter，只保留正文）。
     */
    public String loadSkillContent(String workspaceId, String skillName) {
        String raw = loadSkillRaw(workspaceId, skillName);
        return raw == null ? null : stripFrontmatter(raw);
    }

    /**
     * 加载指定 skill 的原始 Markdown 内容（保留 frontmatter，供 GOAP 元数据解析）。
     */
    public String loadSkillRaw(String workspaceId, String skillName) {
        if (skillName == null || skillName.isBlank()) return null;

        String[] candidates = {
                workspaceId != null ? workspaceSkillDir(workspaceId).resolve(skillName).resolve("SKILL.md").toString() : null,
                workspaceId != null ? workspaceSkillDir(workspaceId).resolve(skillName + ".md").toString() : null,
                SystemHomePaths.globalSkillsDir().resolve(skillName).resolve("SKILL.md").toString(),
                SystemHomePaths.globalSkillsDir().resolve(skillName + ".md").toString(),
        };

        for (String path : candidates) {
            if (path == null) continue;
            String content = tryRead(Path.of(path));
            if (content != null) {
                log.debug("已加载 skill: name={}, path={}", skillName, path);
                return content;
            }
        }

        log.warn("未找到 skill: name={}, workspaceId={}", skillName, workspaceId);
        return null;
    }

    /**
     * 加载多个 skill 并拼接（按传入顺序，去重）。
     */
    public String loadSkillsMerged(String workspaceId, List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) return null;
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        for (String name : skillNames) {
            if (name == null || !seen.add(name)) continue;
            String content = loadSkillContent(workspaceId, name);
            if (content != null && !content.isBlank()) {
                sb.append("## ").append(name).append("\n\n");
                sb.append(content.trim()).append("\n\n");
            }
        }
        return sb.isEmpty() ? null : sb.toString().trim();
    }

    /**
     * 构建指定 Agent 的完整 Skill 块（默认 Skill + 用户 Skill，去重合并）。
     * <p>
     * 返回的文本直接拼到 system prompt 的"执行规范"段落。
     *
     * @param agentName  agent 名称（如 "coding-agent"、"orchestrator"）
     * @param workspaceId 工作区 ID
     * @param userSkill   用户在 UI 选的 Skill（可 null）
     * @return 合并后的 skill 文本块，无 skill 时返回 null
     */
    public String buildAgentSkillBlock(String agentName, String workspaceId, SkillContextData userSkill) {
        List<String> names = new ArrayList<>();
        List<String> defaults = AGENT_DEFAULT_SKILLS.getOrDefault(agentName.toLowerCase(), List.of());
        names.addAll(defaults);
        if (userSkill != null && !userSkill.isEmpty() && userSkill.skillName() != null) {
            names.add(userSkill.skillName());
        }
        String merged = loadSkillsMerged(workspaceId, names);
        if (merged == null) return null;
        return "## 执行规范（Agent: " + agentName + "）\n\n" + merged;
    }

    /**
     * 拼接用户消息与 skill 内容，形成最终 prompt。
     */
    public String buildPromptWithSkill(String workspaceId, String skillName, String userMessage) {
        String skillContent = loadSkillContent(workspaceId, skillName);
        if (skillContent == null || skillContent.isBlank()) {
            return userMessage;
        }
        return """
                ## 执行规范

                请严格遵循以下 Skill 规范来完成你的回答：

                %s

                ---

                ## 用户请求

                %s
                """.formatted(skillContent.trim(), userMessage);
    }

    /** 查询所有可用 skill 名称（仅扫描全局目录，工作区 skill 动态加载） */
    public List<String> listAvailableSkills() {
        Set<String> result = new LinkedHashSet<>();
        Path globalDir = SystemHomePaths.globalSkillsDir();
        if (Files.isDirectory(globalDir)) {
            try (var stream = Files.list(globalDir)) {
                stream.forEach(p -> {
                    String name = p.getFileName().toString();
                    if (Files.isDirectory(p) && Files.isRegularFile(p.resolve("SKILL.md"))) {
                        result.add(name);
                    } else if (name.endsWith(".md")) {
                        result.add(name.substring(0, name.length() - 3));
                    }
                });
            } catch (IOException ignored) {}
        }
        return List.copyOf(result);
    }

    /** 返回 agent 默认 skill 映射（只读视图） */
    public Map<String, List<String>> getAgentDefaultSkills() {
        return Collections.unmodifiableMap(AGENT_DEFAULT_SKILLS);
    }

    private Path workspaceSkillDir(String workspaceId) {
        WorkspaceContext ws = workspaceManager.getWorkspace(workspaceId);
        if (ws == null) return Path.of("");
        return ws.getPath().resolve(".easyClaw/agent/skills");
    }

    private String tryRead(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.debug("读取 skill 失败: {} - {}", path, e.getMessage());
        }
        return null;
    }

    private String stripFrontmatter(String raw) {
        if (raw == null) return null;
        String trimmed = raw.replaceFirst("^\\uFEFF?", "");
        if (!trimmed.startsWith("---")) return trimmed;

        int end = trimmed.indexOf("---", 3);
        if (end < 0) return trimmed;
        String body = trimmed.substring(end + 3).trim();
        return body;
    }
}
