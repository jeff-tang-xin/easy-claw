package com.xinl.easyclaw.scenario;

import com.embabel.agent.api.annotation.Agent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.entity.McpToolEntity;
import com.xinl.easyclaw.mcp.repository.McpServiceRepository;
import com.xinl.easyclaw.mcp.repository.McpToolRepository;
import com.xinl.easyclaw.config.SystemHomePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一的 Action 元数据注册表：
 *   - 内置 SubAgent 的 @Action + @AchievesGoal(@Export(name=...)) 扫描
 *   - MCP HTTP_TOOL 服务动态注册（agentType="mcp"）
 * <p>
 * GOAP 规划器和前端场景编辑器都从此读取统一的 Action 列表。
 */
@Component
public class ActionRegistry implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(ActionRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record ActionMeta(
            String actionId,
            String agentType,
            String agentClassName,
            String description,
            String[] preConditions,
            String[] postConditions,
            boolean readOnly
    ) {}

    public record AgentMeta(
            String agentType,
            String displayName,
            String emoji,
            String description,
            String agentClassName,
            List<ActionMeta> actions
    ) {}

    private static final Map<String, String> AGENT_EMOJI = Map.ofEntries(
            Map.entry("coding", "💻"),
            Map.entry("file", "📁"),
            Map.entry("research", "🔍"),
            Map.entry("content", "✍️"),
            Map.entry("mail", "📧"),
            Map.entry("interaction", "💬"),
            Map.entry("data", "📊"),
            Map.entry("devops", "🚀"),
            Map.entry("verifier", "✅")
    );

    private static final Map<String, String> AGENT_TOOL_SUMMARY = Map.ofEntries(
            Map.entry("coding", "文件读写、Math"),
            Map.entry("file", "文件读写、目录操作"),
            Map.entry("research", "Web 搜索、网页抓取"),
            Map.entry("content", "文件读写"),
            Map.entry("data", "文件读写"),
            Map.entry("devops", "文件读写"),
            Map.entry("mail", "Web 搜索、文件读写"),
            Map.entry("interaction", "无（仅对话/确认）"),
            Map.entry("verifier", "Shell 命令、Maven 编译/测试"),
            Map.entry("mcp", "外部 MCP / REST API"),
            Map.entry("skill", "skill_load 技能加载")
    );


    private final Map<String, ActionMeta> actions = new ConcurrentHashMap<>();
    /** 方法名 → actionId 的映射（Embabel GOAP planner 用方法名，ActionRegistry 用 @Export name） */
    private final Map<String, String> methodNameToActionId = new ConcurrentHashMap<>();
    private final Map<String, String> agentTypeToClass = new ConcurrentHashMap<>();
    private final Map<String, String> agentTypeToDisplayName = new ConcurrentHashMap<>();
    private final Map<String, String> agentTypeToDescription = new ConcurrentHashMap<>();

    private final ApplicationContext applicationContext;
    private final McpServiceRepository mcpRepo;
    private final McpToolRepository toolRepo;

    public ActionRegistry(ApplicationContext applicationContext,
                          @Lazy McpServiceRepository mcpRepo,
                          @Lazy McpToolRepository toolRepo) {
        this.applicationContext = applicationContext;
        this.mcpRepo = mcpRepo;
        this.toolRepo = toolRepo;
    }

    private void scanBuiltinSubAgents() {
        Map<String, Object> agentBeans = applicationContext.getBeansWithAnnotation(Agent.class);
        for (Map.Entry<String, Object> entry : agentBeans.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            Agent agentAnn = clazz.getAnnotation(Agent.class);
            if (agentAnn == null) continue;
            if (!agentAnn.scan()) continue;

            String agentName = agentAnn.name();
            if (agentName == null || agentName.isBlank()) {
                agentName = clazz.getSimpleName();
            }

            if ("orchestrator-agent".equals(agentName) || "OrchestratorAgent".equals(clazz.getSimpleName())) {
                // OrchestratorAgent 不作为子 Agent 注册（不显示给前端场景编辑器），
                // 但其 @Action 方法名需要注册到 methodNameToActionId，
                // 否则 GOAP plan 校验会误判 OrchestratorAgent 自身的 action（如 "chat"）为无效
                scanOrchestratorMethodNames(clazz);
                continue;
            }

            String agentType = agentName.replaceAll("-agent$", "").toLowerCase();
            String displayName = agentName.replace("-agent", "").toUpperCase().charAt(0) + agentName.replace("-agent", "").substring(1) + " Agent";
            agentTypeToDisplayName.put(agentType, displayName);
            agentTypeToDescription.put(agentType, agentAnn.description());
            scan(clazz, agentType);
        }
        log.info("[ActionRegistry] 自动扫描注册了 {} 个子 Agent: {}",
                agentTypeToClass.size(), agentTypeToClass.keySet());
    }

    /**
     * 只扫描 OrchestratorAgent 的 @Action 方法名，注册到 methodNameToActionId。
     * 不加入 actions map（不显示给前端场景编辑器）。
     */
    private void scanOrchestratorMethodNames(Class<?> orchestratorClass) {
        for (Method m : orchestratorClass.getDeclaredMethods()) {
            var actionAnn = m.getAnnotation(com.embabel.agent.api.annotation.Action.class);
            var achievesGoal = m.getAnnotation(com.embabel.agent.api.annotation.AchievesGoal.class);
            if (actionAnn == null || achievesGoal == null) continue;

            String actionId = achievesGoal.export().name();
            if (actionId == null || actionId.isBlank()) continue;

            String methodName = m.getName();
            if (!methodName.equals(actionId) && !methodNameToActionId.containsKey(methodName)) {
                methodNameToActionId.put(methodName, actionId);
                log.debug("注册 OrchestratorAgent 方法名映射: {} -> {}", methodName, actionId);
            }
        }
    }

    private void scan(Class<?> agentClass, String agentType) {
        agentTypeToClass.put(agentType, agentClass.getName());
        for (Method m : agentClass.getDeclaredMethods()) {
            var actionAnn = m.getAnnotation(com.embabel.agent.api.annotation.Action.class);
            var achievesGoal = m.getAnnotation(com.embabel.agent.api.annotation.AchievesGoal.class);
            if (actionAnn == null || achievesGoal == null) continue;

            String actionId = achievesGoal.export().name();
            if (actionId == null || actionId.isBlank()) continue;

            ActionMeta meta = new ActionMeta(
                    actionId,
                    agentType,
                    agentClass.getName(),
                    achievesGoal.description().isBlank() ? actionAnn.description() : achievesGoal.description(),
                    actionAnn.pre(),
                    actionAnn.post(),
                    actionAnn.readOnly()
            );
            actions.put(actionId, meta);
            // 同时注册方法名作为别名（Embabel GOAP planner 用方法名，非 @Export name）
            String methodName = m.getName();
            if (!methodName.equals(actionId)) {
                methodNameToActionId.put(methodName, actionId);
            }
            log.debug("扫描内置 Action: {} (方法: {}) -> {}.{}", actionId, methodName, agentClass.getSimpleName(), methodName);
        }
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        scanBuiltinSubAgents();
        refreshMcpActions();
        refreshSkillActions();
    }

    /**
     * 扫描所有可用 Skill（全局 ~/.easyClaw/skills/），每个 Skill 注册为 agentType="skill" 的 ActionMeta。
     * 这样 GOAP 规划器和前端场景编辑器都能看到所有可用 Skill。
     * Skill 的实际加载由 skill_load 工具完成（OrchestratorAgent 注册）。
     */
    public void refreshSkillActions() {
        actions.entrySet().removeIf(e -> "skill".equals(e.getValue().agentType()));
        AtomicInteger count = new AtomicInteger();
        Path globalDir = SystemHomePaths.globalSkillsDir();
        if (Files.isDirectory(globalDir)) {
            try (var stream = Files.list(globalDir)) {
                stream.forEach(p -> {
                    String name = p.getFileName().toString();
                    Path skillMd = Files.isDirectory(p) ? p.resolve("SKILL.md") : p;
                    if (!Files.isRegularFile(skillMd)) return;
                    String skillName = Files.isDirectory(p) ? name : name.replace(".md", "");
                    String desc = extractSkillDescription(skillMd);
                    ActionMeta meta = new ActionMeta(
                            skillName,
                            "skill",
                            "com.xinl.easyclaw.agent.embabel.SkillLoader",
                            desc.isBlank() ? "加载 Skill " + skillName + " 的执行规范" : desc,
                            new String[0],
                            new String[]{"skill_" + skillName + "_loaded"},
                            true
                    );
                    actions.put(meta.actionId(), meta);
                    count.getAndIncrement();
                });
            } catch (IOException e) {
                log.warn("扫描 Skill 目录失败: {}", e.getMessage());
            }
        }
        if (count.get() > 0) {
            log.info("[ActionRegistry] 已注册 {} 个 Skill 为 Action (agentType=skill)", count);
        }
    }

    private String extractSkillDescription(Path skillMd) {
        try {
            String content = Files.readString(skillMd, StandardCharsets.UTF_8);
            String trimmed = content.replaceFirst("^\\uFEFF?", "");
            if (!trimmed.startsWith("---")) return "";
            int end = trimmed.indexOf("---", 3);
            if (end < 0) return "";
            String frontmatter = trimmed.substring(3, end).trim();
            for (String line : frontmatter.split("\n")) {
                String t = line.trim();
                if (t.startsWith("description:")) {
                    return t.substring("description:".length()).trim().replaceAll("^[\"']|[\"']$", "");
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * 重新扫描所有已启用的 McpTool（子 endpoint），注册为 agentType="mcp" 的 ActionMeta。
     * 如果某个 McpService 还没有 McpTool（旧数据或手动建的 HTTP_TOOL），
     * 则把它的 implementationConfig 当作一个默认 tool 注册，保证向后兼容。
     * 在 McpService / McpTool create/update/delete 后需调用。
     */
    public void refreshMcpActions() {
        actions.entrySet().removeIf(e -> "mcp".equals(e.getValue().agentType()));
        int count = 0;
        try {
            // 优先从 McpToolRepository 扫描
            List<McpToolEntity> allTools = toolRepo.findAll();
            for (McpToolEntity tool : allTools) {
                if (Boolean.FALSE.equals(tool.getEnabled())) continue;
                McpServiceEntity svc = tool.getService();
                if (svc == null || !"HTTP_TOOL".equalsIgnoreCase(svc.resolveTransport())) continue;
                registerToolAsAction(svc, tool);
                count++;
            }

            // 向后兼容：没有任何 McpTool 的 HTTP_TOOL 服务，把 implementationConfig 当作默认 tool
            List<McpServiceEntity> orphanServices = new ArrayList<>();
            for (McpServiceEntity svc : mcpRepo.findAll()) {
                if (!"HTTP_TOOL".equalsIgnoreCase(svc.resolveTransport())) continue;
                if (Boolean.TRUE.equals(svc.getIsTemplate())) continue; // 模板的 tools 由 SystemDataSeeder 管理
                boolean hasTools = allTools.stream().anyMatch(t -> t.getService() != null && t.getService().getId().equals(svc.getId()));
                if (!hasTools && svc.getImplementationConfig() != null && !svc.getImplementationConfig().isBlank()) {
                    orphanServices.add(svc);
                }
            }
            for (McpServiceEntity svc : orphanServices) {
                registerLegacyServiceAsAction(svc);
                count++;
            }
        } catch (Exception e) {
            log.warn("刷新 MCP Action 注册表失败: {}", e.getMessage());
        }
        if (count > 0) {
            log.info("[ActionRegistry] 已注册 {} 个 MCP Tool 为 Action (agentType=mcp)", count);
        }
    }

    private void registerToolAsAction(McpServiceEntity svc, McpToolEntity tool) {
        String configJson = tool.getToolConfig();
        String[] preConditions = new String[0];
        String[] postConditions = new String[0];
        if (configJson != null && !configJson.isBlank()) {
            try {
                Map<String, Object> cfg = MAPPER.readValue(configJson, new TypeReference<>() {});
                if (cfg.get("preconditions") instanceof List<?> pl) {
                    preConditions = pl.stream().map(String::valueOf).toArray(String[]::new);
                }
                if (cfg.get("effects") instanceof List<?> el) {
                    postConditions = el.stream().map(String::valueOf).toArray(String[]::new);
                } else if (cfg.get("postconditions") instanceof List<?> el) {
                    postConditions = el.stream().map(String::valueOf).toArray(String[]::new);
                }
            } catch (Exception ignored) {}
        }
        if (postConditions.length == 0) {
            postConditions = new String[]{"调用了 " + svc.getName() + "." + tool.getToolName()};
        }
        String desc = tool.getDescription() != null && !tool.getDescription().isBlank()
                ? tool.getDescription()
                : (tool.getDisplayName() != null ? tool.getDisplayName() : tool.getToolName());
        ActionMeta meta = new ActionMeta(
                tool.getToolName(),
                "mcp",
                "com.xinl.easyclaw.tools.http.HttpAgentTool",
                desc,
                preConditions,
                postConditions,
                false
        );
        actions.put(meta.actionId(), meta);
    }

    private void registerLegacyServiceAsAction(McpServiceEntity svc) {
        String configJson = svc.getImplementationConfig();
        String[] preConditions = new String[0];
        String[] postConditions = new String[]{"调用了 " + svc.getName()};
        if (configJson != null && !configJson.isBlank()) {
            try {
                Map<String, Object> cfg = MAPPER.readValue(configJson, new TypeReference<>() {});
                if (cfg.get("preconditions") instanceof List<?> pl) {
                    preConditions = pl.stream().map(String::valueOf).toArray(String[]::new);
                }
                if (cfg.get("effects") instanceof List<?> el) {
                    postConditions = el.stream().map(String::valueOf).toArray(String[]::new);
                }
            } catch (Exception ignored) {}
        }
        String desc = svc.getDescription() != null && !svc.getDescription().isBlank()
                ? svc.getDescription()
                : "MCP REST: " + svc.getName();
        ActionMeta meta = new ActionMeta(
                svc.getName(),
                "mcp",
                "com.xinl.easyclaw.tools.http.HttpAgentTool",
                desc,
                preConditions,
                postConditions,
                false
        );
        actions.put(meta.actionId(), meta);
    }

    // ==================== 查询 API ====================

    public Map<String, ActionMeta> getAll() {
        return Collections.unmodifiableMap(actions);
    }

    public List<ActionMeta> listByAgentType(String agentType) {
        return actions.values().stream()
                .filter(a -> a.agentType().equals(agentType))
                .toList();
    }

    public ActionMeta get(String actionId) {
        return actions.get(actionId);
    }

    /**
     * 检查 name 是否是已注册的 action（兼容 actionId 和方法名两种形式）。
     * <p>
     * Embabel GOAP planner 有时用方法名（如 "chat"）而非 @Export name（如 "orchestrate"），
     * 这里同时检查两者。
     */
    public boolean isRegisteredAction(String name) {
        if (name == null || name.isBlank()) return false;
        if (actions.containsKey(name)) return true;
        return methodNameToActionId.containsKey(name);
    }

    /**
     * 返回所有可见的 agentType（内置 SubAgent + "mcp" 分组 + "skill" 分组）。
     */
    public Set<String> allAgentTypes() {
        Set<String> types = new LinkedHashSet<>(agentTypeToClass.keySet());
        if (actions.values().stream().anyMatch(a -> "mcp".equals(a.agentType()))) {
            types.add("mcp");
        }
        if (actions.values().stream().anyMatch(a -> "skill".equals(a.agentType()))) {
            types.add("skill");
        }
        return Collections.unmodifiableSet(types);
    }

    public String agentClassOf(String agentType) {
        return agentTypeToClass.get(agentType);
    }

    public boolean isMcpAction(String actionId) {
        ActionMeta m = actions.get(actionId);
        return m != null && "mcp".equals(m.agentType());
    }


    public String toolSummaryOf(String agentType) {
        return AGENT_TOOL_SUMMARY.getOrDefault(agentType, "未配置");
    }

    /**
     * 返回所有 Agent 分组的元数据（内置 SubAgent + MCP 分组）
     */
    public List<AgentMeta> listAllAgents() {
        List<AgentMeta> result = new ArrayList<>();
        for (String agentType : agentTypeToClass.keySet()) {
            String displayName = agentTypeToDisplayName.getOrDefault(agentType, agentType);
            String description = agentTypeToDescription.getOrDefault(agentType, "");
            String emoji = AGENT_EMOJI.getOrDefault(agentType, "🤖");
            result.add(new AgentMeta(
                    agentType,
                    displayName,
                    emoji,
                    description,
                    agentTypeToClass.get(agentType),
                    listByAgentType(agentType)
            ));
        }
        // MCP 分组
        List<ActionMeta> mcpActions = listByAgentType("mcp");
        result.add(new AgentMeta(
                "mcp",
                "MCP / REST",
                "🔌",
                "外部 REST API 桥接工具",
                "com.xinl.easyclaw.tools.http.HttpAgentTool",
                mcpActions
        ));
        // Skill 分组
        List<ActionMeta> skillActions = listByAgentType("skill");
        result.add(new AgentMeta(
                "skill",
                "技能规范",
                "📚",
                "通过 skill_load 工具加载执行规范，LLM 按需调用",
                "com.xinl.easyclaw.agent.embabel.SkillLoader",
                skillActions
        ));
        return result;
    }

    public Map<String, Object> actionMetaToMap(ActionMeta a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actionId", a.actionId());
        m.put("agentType", a.agentType());
        m.put("description", a.description());
        m.put("pre", a.preConditions());
        m.put("post", a.postConditions());
        m.put("readOnly", a.readOnly());
        return m;
    }

    public Map<String, Object> agentMetaToMap(AgentMeta a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentType", a.agentType());
        m.put("displayName", a.displayName());
        m.put("emoji", a.emoji());
        m.put("description", a.description());
        m.put("className", a.agentClassName());
        List<Map<String, Object>> actionList = new ArrayList<>();
        for (ActionMeta act : a.actions()) {
            actionList.add(actionMetaToMap(act));
        }
        m.put("actions", actionList);
        return m;
    }
}
