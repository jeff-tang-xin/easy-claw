package com.xinl.easyclaw.scenario;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.service.McpConnectionService;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.scenario.repository.ScenarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * AgentType → Orchestrator 顶层 @Export(name) 映射（9 个专用委派 + finalizeTask=orchestrate）
     * 同时用于：resolveEnabledExportNames 白名单 / planConstraints.onlyOnce 默认生成
     */
    public static final Map<String, String> AGENT_TYPE_TO_EXPORT = Map.ofEntries(
            Map.entry("coding", "code-task"),
            Map.entry("file", "file-task"),
            Map.entry("data", "data-task"),
            Map.entry("content", "content-task"),
            Map.entry("mail", "mail-task"),
            Map.entry("research", "research"),
            Map.entry("devops", "devops-task"),
            Map.entry("verify", "verify-task"),
            Map.entry("interaction", "interaction-task"),
            Map.entry("review", "code-task"),
            Map.entry("security", "devops-task")
    );
    /** finalizeTask 对外 export 名（作为唯一出口的收尾，始终允许） */
    public static final String EXPORT_FINALIZE = "orchestrate";

    /**
     * 解析场景最终生效的 @Export name 白名单（用于 GOAP plan 校验 + System Prompt 过滤可用 Action）
     * 规则：enabledAgents → 逐个映射到 Export；始终追加 orchestrate（finalizeTask 收尾必须有）
     * 当 scenario == null 时返回所有 9 个委派 + orchestrate（宽松模式，兼容无场景老工作区）
     */
    public Set<String> resolveEnabledExportNames(ScenarioEntity scenario) {
        Set<String> exports = new LinkedHashSet<>();
        Set<String> agents = resolveEnabledAgentTypes(scenario);
        if (agents.isEmpty()) {
            // 场景为空或无启用能力 → 所有 9 类委派全开（老工作区 / 未绑场景兜底）
            exports.addAll(AGENT_TYPE_TO_EXPORT.values());
        } else {
            for (String agentType : agents) {
                String exp = AGENT_TYPE_TO_EXPORT.get(agentType);
                if (exp != null) exports.add(exp);
            }
        }
        exports.add(EXPORT_FINALIZE); // finalizeTask 收尾，始终允许
        return exports;
    }

    private final ScenarioRepository scenarioRepo;
    private final ActionRegistry actionRegistry;
    private final McpConnectionService mcpService;

    public ScenarioService(ScenarioRepository scenarioRepo,
                           ActionRegistry actionRegistry,
                           McpConnectionService mcpService) {
        this.scenarioRepo = scenarioRepo;
        this.actionRegistry = actionRegistry;
        this.mcpService = mcpService;
    }

    // ==================== 基础 CRUD ====================

    public List<ScenarioEntity> listForUser(String userId) {
        return scenarioRepo.findByOwnerIdOrIsPresetTrue(userId);
    }

    public Optional<ScenarioEntity> findById(String id) {
        return scenarioRepo.findById(id);
    }

    @Transactional
    public ScenarioEntity create(ScenarioEntity entity) {
        if (entity.getId() == null || entity.getId().isBlank()) {
            entity.setId("sc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        }
        if (entity.getCreatedAt() == null) entity.setCreatedAt(Instant.now());
        if (entity.getUpdatedAt() == null) entity.setUpdatedAt(entity.getCreatedAt());
        sanitizeActionBindings(entity);

        return scenarioRepo.save(entity);
    }

    @Transactional
    public ScenarioEntity update(String id, ScenarioEntity updates) {
        ScenarioEntity existing = scenarioRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Scenario not found: " + id));
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getIcon() != null) existing.setIcon(updates.getIcon());
        if (updates.getActionBindings() != null) existing.setActionBindings(updates.getActionBindings());
        if (updates.getMcpBindings() != null) existing.setMcpBindings(updates.getMcpBindings());
        if (updates.getSkills() != null) existing.setSkills(updates.getSkills());
        if (updates.getEnabledAgents() != null) existing.setEnabledAgents(updates.getEnabledAgents());
        existing.setUpdatedAt(Instant.now());
        sanitizeActionBindings(existing);

        return scenarioRepo.save(existing);
    }

    @Transactional
    public void delete(String id) {
        scenarioRepo.deleteById(id);
    }

    // ==================== Action 绑定 ====================

    public record ActionBinding(String actionId, String agentType, boolean enabled, String extra) {
        public ActionBinding(String actionId, String agentType, boolean enabled) {
            this(actionId, agentType, enabled, null);
        }
    }

    public List<ActionBinding> parseActionBindings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析 actionBindings 失败: {}", e.getMessage());
            return List.of();
        }
    }

    public String serializeActionBindings(List<ActionBinding> bindings) {
        try {
            return MAPPER.writeValueAsString(bindings);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 规范化 Action 绑定：只保留当前注册表中真实存在的 Action，
     * 并保留 orchestrate 作为主入口动作；避免无效 actionId 进入场景。
     */
    private void sanitizeActionBindings(ScenarioEntity entity) {
        if (entity.getActionBindings() == null || entity.getActionBindings().isBlank()) {
            return;
        }
        List<ActionBinding> original = parseActionBindings(entity.getActionBindings());
        List<ActionBinding> valid = original.stream()
                .filter(b -> "orchestrator".equalsIgnoreCase(b.agentType())
                        || actionRegistry.get(b.actionId()) != null)
                .toList();
        entity.setActionBindings(serializeActionBindings(valid));
    }


    /**
     * 返回 Scenario 中所有 enabled 的 ActionMeta 列表
     */
    public List<ActionRegistry.ActionMeta> resolveEnabledActions(ScenarioEntity scenario) {
        List<ActionBinding> bindings = parseActionBindings(scenario.getActionBindings());
        return bindings.stream()
                .filter(b -> b.enabled())
                .map(b -> actionRegistry.get(b.actionId()))
                .filter(Objects::nonNull)
                .toList();
    }

    // ==================== MCP 绑定 ====================

    public record McpBinding(String mcpName, boolean enabled) {}

    public List<McpBinding> parseMcpBindings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析 mcpBindings 失败: {}", e.getMessage());
            return List.of();
        }
    }

    public List<McpServiceEntity> resolveEnabledMcps(ScenarioEntity scenario) {
        List<McpBinding> bindings = parseMcpBindings(scenario.getMcpBindings());
        Set<String> enabledNames = bindings.stream()
                .filter(b -> b.enabled())
                .map(McpBinding::mcpName)
                .collect(java.util.stream.Collectors.toSet());
        if (enabledNames.isEmpty()) return List.of();

        try {
            return mcpService.findAll().stream()
                    .filter(m -> enabledNames.contains(m.getName()))
                    .filter(m -> Boolean.TRUE.equals(m.getIsConnected()))
                    .toList();
        } catch (Exception e) {
            log.warn("解析 MCP 绑定失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== Skill ====================

    public List<String> parseSkills(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析 skills 失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== Enabled Agents ====================

    public List<String> parseEnabledAgents(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析 enabledAgents 失败: {}", e.getMessage());
            return List.of();
        }
    }

    public String serializeEnabledAgents(List<String> agents) {
        try {
            return MAPPER.writeValueAsString(agents);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 解析 Scenario 最终生效的 Agent 类型集合
     * 优先使用 enabledAgents 字段；为空时回退到从 actionBindings 推断
     */
    public Set<String> resolveEnabledAgentTypes(ScenarioEntity scenario) {
        if (scenario == null) {
            return new LinkedHashSet<>(actionRegistry.allAgentTypes());
        }
        List<String> enabledAgents = parseEnabledAgents(scenario.getEnabledAgents());
        if (!enabledAgents.isEmpty()) {
            Set<String> result = new LinkedHashSet<>();
            for (String t : enabledAgents) {
                if (actionRegistry.allAgentTypes().contains(t)) {
                    result.add(t);
                }
            }
            if (!result.isEmpty()) return result;
        }
        Set<String> inferred = new LinkedHashSet<>();
        for (ActionBinding b : parseActionBindings(scenario.getActionBindings())) {
            if (b.enabled() && b.agentType() != null && !"orchestrator".equals(b.agentType())) {
                inferred.add(b.agentType());
            }
        }
        return inferred;
    }

    // ==================== 初始化预设场景 ====================

    @Transactional
    public void ensurePresets() {
        createOrUpdatePreset("general", "通用助手", "基础问答、文件操作、网络搜索", "🤖",
                """
                [{"actionId":"orchestrate","agentType":"orchestrator","enabled":true},
                  {"actionId":"code-analyze","agentType":"coding","enabled":true},
                  {"actionId":"code-refactor","agentType":"coding","enabled":true},
                  {"actionId":"test-write","agentType":"coding","enabled":true},
                  {"actionId":"code-review","agentType":"coding","enabled":true},
                  {"actionId":"file-search","agentType":"file","enabled":true},
                  {"actionId":"file-organize","agentType":"file","enabled":true},
                  {"actionId":"file-batch-edit","agentType":"file","enabled":true},
                  {"actionId":"research","agentType":"research","enabled":true},
                  {"actionId":"web-api-discovery","agentType":"research","enabled":true},
                  {"actionId":"outline","agentType":"content","enabled":true},
                  {"actionId":"draft","agentType":"content","enabled":true},
                  {"actionId":"revise","agentType":"content","enabled":true},
                  {"actionId":"summarize","agentType":"content","enabled":true},
                  {"actionId":"annotate","agentType":"interaction","enabled":true},
                  {"actionId":"confirm","agentType":"interaction","enabled":true}]
                """,
                "[]",
                """
                ["karpathy-guidelines","cursor-rules"]
                """,
                """
                ["coding","file","research","content","interaction"]
                """);

        createOrUpdatePreset("coding", "编码专家", "专注代码分析、重构、测试、评审", "💻",
                """
                [{"actionId":"orchestrate","agentType":"orchestrator","enabled":true},
                  {"actionId":"code-analyze","agentType":"coding","enabled":true},
                  {"actionId":"code-refactor","agentType":"coding","enabled":true},
                  {"actionId":"test-write","agentType":"coding","enabled":true},
                  {"actionId":"code-review","agentType":"coding","enabled":true},
                  {"actionId":"file-search","agentType":"file","enabled":true},
                  {"actionId":"file-organize","agentType":"file","enabled":true},
                  {"actionId":"file-batch-edit","agentType":"file","enabled":true},
                  {"actionId":"annotate","agentType":"interaction","enabled":true},
                  {"actionId":"confirm","agentType":"interaction","enabled":true}]
                """,
                "[]",
                """
                ["code-refactor","backend-architecture","code-review"]
                """,
                """
                ["coding","file","verify","interaction"]
                """);

        createOrUpdatePreset("weekly-report", "周报助手", "自动汇总工作内容生成周报", "📝",
                """
                [{"actionId":"orchestrate","agentType":"orchestrator","enabled":true},
                  {"actionId":"email-collect","agentType":"mail","enabled":true},
                  {"actionId":"email-extract","agentType":"mail","enabled":true},
                  {"actionId":"email-classify","agentType":"mail","enabled":true},
                  {"actionId":"code-analyze","agentType":"coding","enabled":true},
                  {"actionId":"summarize","agentType":"content","enabled":true},
                  {"actionId":"outline","agentType":"content","enabled":true},
                  {"actionId":"draft","agentType":"content","enabled":true},
                  {"actionId":"annotate","agentType":"interaction","enabled":true}]
                """,
                "[]",
                """
                ["summarize"]
                """,
                """
                ["mail","coding","content","interaction"]
                """);

        createOrUpdatePreset("content-create", "内容创作", "大纲、写作、修改、润色", "✍️",
                """
                [{"actionId":"orchestrate","agentType":"orchestrator","enabled":true},
                  {"actionId":"outline","agentType":"content","enabled":true},
                  {"actionId":"draft","agentType":"content","enabled":true},
                  {"actionId":"revise","agentType":"content","enabled":true},
                  {"actionId":"summarize","agentType":"content","enabled":true},
                  {"actionId":"research","agentType":"research","enabled":true},
                  {"actionId":"annotate","agentType":"interaction","enabled":true},
                  {"actionId":"confirm","agentType":"interaction","enabled":true}]
                """,
                "[]",
                "[]",
                """
                ["content","research","interaction"]
                """);

        createOrUpdatePreset("mail-triage", "邮件分拣", "收集、提取、分类、回复邮件", "📧",
                """
                [{"actionId":"orchestrate","agentType":"orchestrator","enabled":true},
                  {"actionId":"email-collect","agentType":"mail","enabled":true},
                  {"actionId":"email-extract","agentType":"mail","enabled":true},
                  {"actionId":"email-classify","agentType":"mail","enabled":true},
                  {"actionId":"send-email","agentType":"mail","enabled":true},
                  {"actionId":"summarize","agentType":"content","enabled":true},
                  {"actionId":"confirm","agentType":"interaction","enabled":true}]
                """,
                "[]",
                "[]",
                """
                ["mail","content","interaction"]
                """);

        createOrUpdatePreset("data-analysis", "数据分析", "数据收集、清洗、分析、报告", "📊",
                """
                [{"actionId":"orchestrate","agentType":"orchestrator","enabled":true},
                  {"actionId":"data-collect","agentType":"data","enabled":true},
                  {"actionId":"data-clean","agentType":"data","enabled":true},
                  {"actionId":"data-analyze","agentType":"data","enabled":true},
                  {"actionId":"data-report","agentType":"data","enabled":true},
                  {"actionId":"summarize","agentType":"content","enabled":true},
                  {"actionId":"research","agentType":"research","enabled":true},
                  {"actionId":"confirm","agentType":"interaction","enabled":true}]
                """,
                "[]",
                "[]",
                """
                ["data","content","research","interaction"]
                """);

        createOrUpdatePreset("devops", "DevOps 助手", "CI/CD、部署、监控、回滚", "🚀",
                """
                [{"actionId":"orchestrate","agentType":"orchestrator","enabled":true},
                  {"actionId":"cicd-create","agentType":"devops","enabled":true},
                  {"actionId":"deploy","agentType":"devops","enabled":true},
                  {"actionId":"monitor","agentType":"devops","enabled":true},
                  {"actionId":"rollback","agentType":"devops","enabled":true},
                  {"actionId":"code-analyze","agentType":"coding","enabled":true},
                  {"actionId":"confirm","agentType":"interaction","enabled":true}]
                """,
                "[]",
                "[]",
                """
                ["devops","coding","verify","interaction"]
                """);
        normalizePresets();


        log.info("[ScenarioService] 预设场景初始化完成，共 {} 个", scenarioRepo.findByIsPresetTrue().size());
    }


    /**
     * 标准化所有预设场景：修正无效 actionId、统一步骤顺序、保证字段完整。
     * 每次启动都会将预设场景重置为规范定义，避免旧库残留不合理配置。
     */
    /**
     * 解析 ScenarioEntity.planConstraints JSON → Map；null/空/解析失败返回宽松默认约束（只拦截 orchestrate 首步）。
     * 返回 Map 的 key 全部小写驼峰，取值均为强类型（List<String>/Integer/Map）。
     */
    public Map<String, Object> resolvePlanConstraints(ScenarioEntity scenario) {
        if (scenario == null || scenario.getPlanConstraints() == null
                || scenario.getPlanConstraints().isBlank()) {
            return defaultConstraints(null);
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(scenario.getPlanConstraints(),
                    new TypeReference<Map<String, Object>>() {});
            return mergeWithDefaults(parsed, scenario);
        } catch (Exception e) {
            log.warn("解析 planConstraints 失败(回退默认): id={}, err={}", scenario.getId(), e.getMessage());
            return defaultConstraints(scenario);
        }
    }

    /** 宽松默认约束：maxSteps=5 + 首步禁止 orchestrate + all enabledAgents 只出现 1 次 + 空 orderRules */
    private Map<String, Object> defaultConstraints(ScenarioEntity scenario) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxSteps", 5);
        m.put("firstStepCannotBe", new ArrayList<>(List.of("orchestrate")));
        Set<String> enabled = scenario != null ? resolveEnabledAgentTypes(scenario) : Set.of();
        List<String> onlyOnce = new ArrayList<>();
        enabled.forEach(a -> {
            String aid = AGENT_TYPE_TO_EXPORT.get(a);
            if (aid != null) onlyOnce.add(aid);
        });
        onlyOnce.add("orchestrate");
        m.put("onlyOnce", onlyOnce);
        m.put("orderRules", new LinkedHashMap<String, List<String>>());
        return m;
    }

    /** 用户配的 constraints 覆盖到默认约束上（缺失字段自动补默认，防止用户漏写导致校验绕过） */
    private Map<String, Object> mergeWithDefaults(Map<String, Object> user, ScenarioEntity scenario) {
        Map<String, Object> def = defaultConstraints(scenario);
        for (var e : def.entrySet()) {
            user.putIfAbsent(e.getKey(), e.getValue());
        }
        // 值类型归一：maxSteps 保证 Integer，列表保证 ArrayList<String>
        Object ms = user.get("maxSteps");
        user.put("maxSteps", ms instanceof Number ? ((Number) ms).intValue() : 5);
        user.put("firstStepCannotBe", toStrList(user.get("firstStepCannotBe"), (List<String>) def.get("firstStepCannotBe")));
        user.put("onlyOnce", toStrList(user.get("onlyOnce"), (List<String>) def.get("onlyOnce")));
        if (!(user.get("orderRules") instanceof Map)) {
            user.put("orderRules", new LinkedHashMap<String, List<String>>());
        } else {
            Map<String, Object> or = new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            var raw = (Map<String, Object>) user.get("orderRules");
            for (var kv : raw.entrySet()) {
                or.put(kv.getKey(), toStrList(kv.getValue(), List.of()));
            }
            user.put("orderRules", or);
        }
        return user;
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStrList(Object v, List<String> fallback) {
        if (!(v instanceof List<?> l)) return fallback;
        List<String> out = new ArrayList<>();
        for (Object o : l) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    private void normalizePresets() {
        // ===================== preset_general（通用助手） =====================
        // 宽松模式：只保证 maxSteps + 首步不能 orchestrate + 各 action 唯一
        normalizePreset("preset_general", "general", "通用助手",
                "标准工作流：理解需求 → 代码/文件处理 → 信息检索 → 内容产出 → 确认", "🤖",
                List.of(
                        new ActionBinding("orchestrate", "orchestrator", true),
                        new ActionBinding("code-task", "coding", true),
                        new ActionBinding("file-task", "file", true),
                        new ActionBinding("research", "research", true),
                        new ActionBinding("content-task", "content", true),
                        new ActionBinding("interaction-task", "interaction", true)
                ),
                List.of("karpathy-guidelines", "cursor-rules"),
                List.of("coding", "file", "research", "content", "interaction"),
                Map.of(
                        "maxSteps", 5,
                        "firstStepCannotBe", List.of("orchestrate"),
                        "orderRules", Map.of()
                ));

        // ===================== preset_coding（编码专家） =====================
        // 强约束：code → verify → confirm；首步不能 verify/chat；verify 必须 code 之后
        normalizePreset("preset_coding", "coding", "编码专家",
                "标准工作流：理解需求 → 代码分析 → 代码修改 → 文件检索 → 确认", "💻",
                List.of(
                        new ActionBinding("orchestrate", "orchestrator", true),
                        new ActionBinding("code-task", "coding", true),
                        new ActionBinding("file-task", "file", true),
                        new ActionBinding("verify-task", "verify", true),
                        new ActionBinding("interaction-task", "interaction", true)
                ),
                List.of("code-refactor", "backend-architecture", "cursor-rules"),
                List.of("coding", "file", "verify", "interaction"),
                Map.of(
                        "maxSteps", 4,
                        "firstStepCannotBe", List.of("orchestrate", "verify-task", "interaction-task"),
                        "orderRules", Map.of(
                                "verify-task",      List.of("code-task"),
                                "interaction-task", List.of("code-task", "verify-task")
                        )
                ));

        // ===================== preset_weekly-report（周报助手） =====================
        // 强约束：mail/file(素材收集) → content(写周报) → interaction(确认)
        normalizePreset("preset_weekly-report", "weekly-report", "周报助手",
                "标准工作流：收集邮件/工作记录 → 提取分类 → 汇总 → 生成周报 → 确认", "📝",
                List.of(
                        new ActionBinding("orchestrate", "orchestrator", true),
                        new ActionBinding("mail-task", "mail", true),
                        new ActionBinding("file-task", "file", true),
                        new ActionBinding("content-task", "content", true),
                        new ActionBinding("interaction-task", "interaction", true)
                ),
                List.of(),
                List.of("mail", "file", "content", "interaction"),
                Map.of(
                        "maxSteps", 4,
                        "firstStepCannotBe", List.of("orchestrate", "content-task", "interaction-task"),
                        "orderRules", Map.of(
                                "content-task",     List.of("mail-task", "file-task"),
                                "interaction-task", List.of("content-task")
                        )
                ));

        // ===================== preset_content-create（内容创作） =====================
        // 强约束：research → content → interaction
        normalizePreset("preset_content-create", "content-create", "内容创作",
                "标准工作流：调研 → 大纲 → 初稿 → 修改 → 总结 → 确认", "✍️",
                List.of(
                        new ActionBinding("orchestrate", "orchestrator", true),
                        new ActionBinding("research", "research", true),
                        new ActionBinding("content-task", "content", true),
                        new ActionBinding("interaction-task", "interaction", true)
                ),
                List.of(),
                List.of("research", "content", "interaction"),
                Map.of(
                        "maxSteps", 4,
                        "firstStepCannotBe", List.of("orchestrate", "content-task", "interaction-task"),
                        "orderRules", Map.of(
                                "content-task",     List.of("research"),
                                "interaction-task", List.of("content-task")
                        )
                ));

        // ===================== preset_mail-triage（邮件分拣） =====================
        // 强约束：mail(收件) → content(分类/写回复) → interaction(确认)
        normalizePreset("preset_mail-triage", "mail-triage", "邮件分拣",
                "标准工作流：收集邮件 → 提取信息 → 分类 → 摘要 → 回复/确认", "📧",
                List.of(
                        new ActionBinding("orchestrate", "orchestrator", true),
                        new ActionBinding("mail-task", "mail", true),
                        new ActionBinding("content-task", "content", true),
                        new ActionBinding("interaction-task", "interaction", true)
                ),
                List.of(),
                List.of("mail", "content", "interaction"),
                Map.of(
                        "maxSteps", 4,
                        "firstStepCannotBe", List.of("orchestrate", "content-task", "interaction-task"),
                        "orderRules", Map.of(
                                "content-task",     List.of("mail-task"),
                                "interaction-task", List.of("mail-task", "content-task")
                        )
                ));

        // ===================== preset_data-analysis（数据分析） =====================
        // 强约束：research/data → content(报告) → interaction(确认)
        normalizePreset("preset_data-analysis", "data-analysis", "数据分析",
                "标准工作流：数据收集 → 清洗 → 分析 → 报告 → 总结 → 确认", "📊",
                List.of(
                        new ActionBinding("orchestrate", "orchestrator", true),
                        new ActionBinding("data-task", "data", true),
                        new ActionBinding("content-task", "content", true),
                        new ActionBinding("research", "research", true),
                        new ActionBinding("interaction-task", "interaction", true)
                ),
                List.of(),
                List.of("data", "content", "research", "interaction"),
                Map.of(
                        "maxSteps", 5,
                        "firstStepCannotBe", List.of("orchestrate", "content-task", "interaction-task"),
                        "orderRules", Map.of(
                                "content-task",     List.of("data-task", "research"),
                                "interaction-task", List.of("content-task", "data-task")
                        )
                ));

        // ===================== preset_devops（DevOps 助手） =====================
        // 强约束：code/devops(部署) → interaction(确认)；verify 必须 deploy 之后
        normalizePreset("preset_devops", "devops", "DevOps 助手",
                "标准工作流：代码分析 → 流水线创建 → 部署 → 监控 → 回滚 → 确认", "🚀",
                List.of(
                        new ActionBinding("orchestrate", "orchestrator", true),
                        new ActionBinding("devops-task", "devops", true),
                        new ActionBinding("code-task", "coding", true),
                        new ActionBinding("interaction-task", "interaction", true)
                ),
                List.of("devops-cicd"),
                List.of("devops", "coding", "interaction"),
                Map.of(
                        "maxSteps", 5,
                        "firstStepCannotBe", List.of("orchestrate", "interaction-task"),
                        "orderRules", Map.of(
                                "interaction-task", List.of("devops-task", "code-task")
                        )
                ));
    }

    private void normalizePreset(String id, String intent, String name, String desc, String icon,
                                 List<ActionBinding> actions, List<String> skills, List<String> agents,
                                 Map<String, Object> constraints) {
        ScenarioEntity entity = scenarioRepo.findById(id).orElse(null);
        if (entity == null) {
            entity = ScenarioEntity.builder()
                    .id(id)
                    .name(name)
                    .description(desc)
                    .icon(icon)
                    .intent(intent)
                    .isPreset(true)
                    .build();
        }
        entity.setName(name);
        entity.setDescription(desc);
        entity.setIcon(icon);
        entity.setIntent(intent);
        entity.setActionBindings(serializeActionBindings(actions));
        entity.setMcpBindings("[]");
        try {
            entity.setSkills(MAPPER.writeValueAsString(skills));
            entity.setEnabledAgents(MAPPER.writeValueAsString(agents));
            entity.setPlanConstraints(MAPPER.writeValueAsString(constraints != null ? constraints : Map.of()));
        } catch (Exception e) {
            log.warn("序列化预设场景 JSON 失败: {}", e.getMessage());
            entity.setSkills("[]");
            entity.setEnabledAgents("[]");
            entity.setPlanConstraints("{}");
        }
        entity.setIsPreset(true);
        entity.setUpdatedAt(Instant.now());
        scenarioRepo.save(entity);
    }

    private void createOrUpdatePreset(String intent, String name, String desc, String icon,
                                      String actionBindings, String mcpBindings, String skills,
                                      String enabledAgents) {
        String id = "preset_" + intent;
        ScenarioEntity entity = scenarioRepo.findById(id).orElse(null);
        if (entity == null) {
            entity = ScenarioEntity.builder()
                    .id(id)
                    .name(name)
                    .description(desc)
                    .icon(icon)
                    .intent(intent)
                    .actionBindings(actionBindings.strip())
                    .mcpBindings(mcpBindings.strip())
                    .skills(skills.strip())
                    .enabledAgents(enabledAgents.strip())
                    .isPreset(true)
                    .build();
        } else {
            boolean changed = false;
            if (entity.getEnabledAgents() == null || entity.getEnabledAgents().isBlank()) {
                entity.setEnabledAgents(enabledAgents.strip());
                changed = true;
            }
            if (entity.getDescription() == null || entity.getDescription().isBlank()) {
                entity.setDescription(desc);
                changed = true;
            }
            if (!icon.equals(entity.getIcon())) {
                entity.setIcon(icon);
                changed = true;
            }
            if (changed) {
                entity.setUpdatedAt(Instant.now());
            }
        }
        scenarioRepo.save(entity);
    }
}
