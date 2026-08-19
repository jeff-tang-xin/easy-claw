package com.xinl.easyclaw.agent.embabel;

import com.xinl.easyclaw.agent.embabel.domain.HistoryContextData;
import com.xinl.easyclaw.agent.embabel.domain.MemoryContextData;
import com.xinl.easyclaw.agent.embabel.domain.WorkspaceContextData;
import com.xinl.easyclaw.scenario.ActionRegistry;
import com.xinl.easyclaw.scenario.ScenarioService;
import com.xinl.easyclaw.scenario.ScenarioService.ActionBinding;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.skill.service.SkillResolver;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 负责根据 Workspace / Scenario / History / Memory 组装主智能体 System Prompt。
 * <p>
 * 从 OrchestratorAgent 中拆出，保持编排器只关注工具装配与 GOAP 入口。
 */
@Component
public class SystemPromptBuilder {



    public static final String FILE_TOOL_GUIDE = """
            ## 文件工具调用规范
            - 调用 `createFile` / `writeFile` 时必须同时提供 `path`（相对当前工作区的路径）和 `content`。
            - 参数名必须是 `path`，不要写成 `filePath`、`file_path`、`fileName` 等别名。
            - `path` 不能省略；创建文件前如目录不存在，先调用 `createDirectory`。
            """;



    private final SkillResolver skillResolver;
    private final ScenarioService scenarioService;
    private final ActionRegistry actionRegistry;

    public SystemPromptBuilder(SkillResolver skillResolver,
                               ScenarioService scenarioService,
                               ActionRegistry actionRegistry) {
        this.skillResolver = skillResolver;
        this.scenarioService = scenarioService;
        this.actionRegistry = actionRegistry;
    }

    public String build(WorkspaceContextData ws, Path wsPath, ScenarioEntity scenario,
                        HistoryContextData history, MemoryContextData memory) {
        StringBuilder sb = new StringBuilder();

        String scenarioName = scenario != null ? scenario.getName() : "通用助手";
        String scenarioIcon = scenario != null && scenario.getIcon() != null ? scenario.getIcon() : "🤖";
        String scenarioDesc = scenario != null && scenario.getDescription() != null
                ? scenario.getDescription() : "处理各种工作任务";

        sb.append("你是 Easy-Claw 的主智能体，当前场景：**").append(scenarioIcon).append(" ").append(scenarioName).append("**\n\n");
        sb.append("场景说明：").append(scenarioDesc).append("\n\n");

        sb.append("## GOAP 规划原则\n");
        sb.append("1. 先理解用户意图，再选择最合适的子 Agent/工具执行\n");
        sb.append("2. 复杂任务拆解为多步，每步结束后检查状态再继续\n");
        sb.append("3. 需要用户确认/批注时，先暂停并输出审阅内容\n");
        sb.append("4. 简单问题直接回答，不调用工具\n\n");

        sb.append("## 当前工作区\n");
        sb.append("- 路径：").append(wsPath).append("\n");
        if (scenario != null) {
            sb.append("- 场景：").append(scenario.getName()).append(" (").append(scenario.getIntent()).append(")\n");
        }

        sb.append("\n").append(FILE_TOOL_GUIDE).append("\n");


        // 可用 Skill（工作区 + 全局，正文由 LLM 调 skill_load 按需加载 —— 渐进式加载设计）
        List<String> allSkillNames = skillResolver.listSkillNames(ws != null ? ws.workspaceId() : null);
        if (!allSkillNames.isEmpty()) {
            sb.append("\n## 📚 可用技能 (").append(allSkillNames.size()).append(" 个)\n");
            sb.append("使用 `skill_list_skills` 查看完整资源列表，使用 `skill_load(skillId, path)` 按需加载。\n\n");
            for (String skillName : allSkillNames) {
                sb.append("- **").append(skillName).append("**");
                Path skillFile = skillResolver.resolveSkillFile(ws != null ? ws.workspaceId() : null, skillName);
                String desc = skillFile != null ? skillResolver.extractDescription(skillFile) : "";
                if (!desc.isBlank()) {
                    sb.append(" — ").append(desc, 0, Math.min(desc.length(), 100));
                }
                sb.append("\n");
            }
        }

        // Scenario 绑定的 Skill：告诉 LLM 这些是推荐优先加载的
        List<String> boundSkills = scenario != null
                ? scenarioService.parseSkills(scenario.getSkills())
                : List.of();
        if (boundSkills.isEmpty() && ws != null && ws.activeSkills() != null) {
            boundSkills = ws.activeSkills();
        }
        if (!boundSkills.isEmpty()) {
            sb.append("\n### 🎯 推荐优先加载\n");
            for (String s : boundSkills) {
                sb.append("- `skill_load(\"").append(s).append("\", \"SKILL.md\")`\n");
            }
        }

        // 可用子 Agent / MCP（按 Scenario 绑定，统一展示）
        Set<String> enabledAgentTypes = scenarioService.resolveEnabledAgentTypes(scenario);
        // 角色能力边界：限制可见的子 Agent 分组
        if (ws != null && ws.allowedAgentTypes() != null && !ws.allowedAgentTypes().isEmpty()) {
            enabledAgentTypes.retainAll(ws.allowedAgentTypes());
        }
        // 同时加入 scenario.actionBindings 中 agentType=mcp 的条目（resolveEnabledAgentTypes 不包含 mcp）
        if (scenario != null) {
            for (ActionBinding b : scenarioService.parseActionBindings(scenario.getActionBindings())) {
                if (b.enabled() && "mcp".equals(b.agentType())) {
                    enabledAgentTypes.add("mcp");
                    break;
                }
            }
        }

        sb.append("\n## 🤖 子 Agent 选择指南\n");
        sb.append("根据任务类型直接选择最合适的 Agent，不要逐个尝试；每个 Agent 的职责、可用工具和 Action 如下：\n");
        sb.append("| Agent | 职责 | 可用工具 | Actions |\n");
        sb.append("|---|---|---|---|\n");
        for (ActionRegistry.AgentMeta meta : actionRegistry.listAllAgents()) {
            if (!enabledAgentTypes.contains(meta.agentType())) continue;
            List<String> actionNames = meta.actions().stream()
                    .map(ActionRegistry.ActionMeta::actionId)
                    .toList();
            sb.append("| ").append(meta.emoji()).append(" ").append(meta.displayName())
                    .append(" | ").append(meta.description() == null ? "" : meta.description())
                    .append(" | ").append(actionRegistry.toolSummaryOf(meta.agentType()))
                    .append(" | `").append(String.join(", ", actionNames)).append("` |\n");
        }

        sb.append("\n## 可用 Action (").append(enabledAgentTypes.size()).append(" 组)\n");
        for (String ag : enabledAgentTypes) {
            String label = "mcp".equals(ag) ? "🔌 MCP / REST API" : ag + "-agent";
            sb.append("- **").append(label).append("**\n");
            List<ActionRegistry.ActionMeta> actions = actionRegistry.listByAgentType(ag);
            for (ActionRegistry.ActionMeta a : actions) {
                if ("mcp".equals(ag) && ws != null && ws.allowedMcpTools() != null && !ws.allowedMcpTools().isEmpty()) {
                    if (!ws.allowedMcpTools().contains(a.actionId())) {
                        continue;
                    }
                }
                sb.append("    - `").append(a.actionId()).append("` — ")
                        .append(a.description(), 0, Math.min(a.description().length(), 80)).append("\n");
            }
        }

        // 兼容旧 scenario.mcpBindings：如果 actionBindings 没有 mcp 条目但 mcpBindings 有，提示已废弃
        if (scenario != null) {
            List<ScenarioService.McpBinding> oldBindings = scenarioService.parseMcpBindings(scenario.getMcpBindings());
            boolean hasOldMcp = oldBindings.stream().anyMatch(ScenarioService.McpBinding::enabled);
            if (hasOldMcp && !enabledAgentTypes.contains("mcp")) {
                sb.append("\n⚠️ 检测到旧版 MCP 绑定已迁移至统一的 Action 列表，请在场景编辑器中重新勾选 MCP / REST 分组下的 Action。\n");
            }
        }

        if (ws != null && ws.roleSystemPrompt() != null && !ws.roleSystemPrompt().isBlank()) {
            sb.append("\n## 角色设定\n").append(ws.roleSystemPrompt()).append("\n");
        }

        if (history != null && !history.messages().isEmpty()) {
            sb.append("\n## 对话历史（").append(history.messages().size()).append(" 轮）\n");
            for (HistoryContextData.HistoryMessage h : history.messages()) {
                String role = "user".equals(h.role()) ? "用户" : "助手";
                sb.append("**").append(role).append("**: ").append(h.content()).append("\n\n");
            }
        }

        if (memory != null && !memory.items().isEmpty()) {
            sb.append("\n## 用户记忆\n");
            for (MemoryContextData.MemoryItem m : memory.items()) {
                sb.append("- [").append(m.type()).append("] ").append(m.content()).append("\n");
            }
        }

        if (ws != null && ws.disabledTools() != null && !ws.disabledTools().isEmpty()) {
            sb.append("\n\n注意：以下工具已禁用：").append(String.join(", ", ws.disabledTools()));
        }

        return sb.toString();
    }
}
