package com.xinl.easyclaw.agent.embabel;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.Tool.InputSchema;
import com.embabel.agent.api.tool.Tool.Metadata;
import com.embabel.agent.api.tool.Tool.Parameter;
import com.embabel.agent.api.tool.Tool.ParameterType;
import com.embabel.agent.api.tool.Tool.Result;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.skill.service.SkillResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 构建 Skill 加载相关工具。
 * <p>
 * 提供两个通用工具：
 * <ul>
 *   <li>skill_list_skills() —— 列出当前工作区 + 全局所有可用 skill（name + description + 可用子文件列表）</li>
 *   <li>skill_load(skillId, path) —— 加载指定 skill 的 SKILL.md 或子文件内容</li>
 * </ul>
 */
@Component
public class SkillToolFactory {

    private static final Logger log = LoggerFactory.getLogger(SkillToolFactory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillResolver skillResolver;

    public SkillToolFactory(SkillResolver skillResolver) {
        this.skillResolver = skillResolver;
    }

    public Tool skillListTool(String workspaceId) {
        final String wsId = workspaceId;
        return Tool.create(
                "skill_list_skills",
                "列出所有可用 Skill，返回每个 Skill 的 name、description 和可用子文件列表。在选择要用哪个 Skill 之前先调用此工具。",
                InputSchema.of(new Parameter[0]),
                Metadata.create(),
                json -> Result.text(skillResolver.scanAllSkills(wsId))
        );
    }

    public Tool skillLoadTool(String workspaceId) {
        final String wsId = workspaceId;
        return Tool.create(
                "skill_load",
                "加载指定 Skill 的内容。skillId 是技能名称，path 是相对路径（默认 'SKILL.md'）。返回文件的 Markdown 正文。如果 path 错误会返回该 Skill 下所有可用文件列表。",
                InputSchema.of(
                        new Parameter("skillId", ParameterType.STRING, "Skill 名称，如 'code-refactor'、'vercel-react-best-practices'", true, List.of(), List.of(), null),
                        new Parameter("path", ParameterType.STRING, "要加载的文件路径，相对 Skill 根目录。默认 'SKILL.md'，子文件如 'async-parallel.md' 或 'references/style-guide.md'", false, List.of(), List.of())
                ),
                Metadata.create(),
                json -> loadSkillResource(wsId, json)
        );
    }

    private Result loadSkillResource(String workspaceId, String jsonInput) {
        String skillId = null;
        String path = "SKILL.md";
        if (jsonInput != null && !jsonInput.isBlank()) {
            try {
                Map<String, Object> params = MAPPER.readValue(jsonInput, new TypeReference<>() {});
                skillId = (String) params.get("skillId");
                Object p = params.get("path");
                if (p != null) path = String.valueOf(p);
            } catch (Exception e) {
                return Result.error("参数解析失败: " + e.getMessage());
            }
        }
        if (skillId == null || skillId.isBlank()) {
            return Result.error("skillId 参数不能为空");
        }

        Path skillDir = skillResolver.resolveSkillDir(workspaceId, skillId);
        if (skillDir == null) {
            return Result.error("未找到 Skill: " + skillId + "。可用 Skills:\n" + skillResolver.scanAllSkills(workspaceId));
        }

        Path target = skillDir.resolve(path).normalize();
        if (!target.startsWith(skillDir)) {
            return Result.error("路径越权: " + path);
        }

        // 单文件 Skill 兼容：默认 SKILL.md 不存在时回退到 {skillId}.md
        if (!java.nio.file.Files.isRegularFile(target) && "SKILL.md".equals(path)) {
            Path singleFile = skillDir.resolve(skillId + ".md");
            if (java.nio.file.Files.isRegularFile(singleFile)) {
                target = singleFile;
            }
        }

        if (!java.nio.file.Files.isRegularFile(target)) {
            List<String> available = skillResolver.listAvailableFiles(skillDir);
            StringBuilder sb = new StringBuilder();
            sb.append("文件未找到: ").append(path).append("\n\n");
            sb.append("Skill [").append(skillId).append("] 可用文件：\n");
            for (String f : available) {
                sb.append("- `").append(f).append("`\n");
            }
            return Result.text(sb.toString().trim());
        }

        String content = skillResolver.readSkillResource(workspaceId, skillId, path);
        if (content == null) {
            return Result.error("读取文件失败: " + path);
        }
        return Result.text("## Skill: " + skillId + " (" + path + ")\n\n" + content);
    }
}
