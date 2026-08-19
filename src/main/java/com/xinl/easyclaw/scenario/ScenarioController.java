package com.xinl.easyclaw.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioService scenarioService;
    private final ActionRegistry actionRegistry;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ScenarioController(ScenarioService scenarioService, ActionRegistry actionRegistry) {
        this.scenarioService = scenarioService;
        this.actionRegistry = actionRegistry;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String userId) {
        List<ScenarioEntity> scenarios = scenarioService.listForUser(userId != null ? userId : "anonymous");
        List<Map<String, Object>> result = new ArrayList<>();
        for (ScenarioEntity s : scenarios) {
            result.add(toMap(s));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return scenarioService.findById(id)
                .map(s -> ResponseEntity.ok(toMap(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        ScenarioEntity entity = new ScenarioEntity();
        applyPayload(entity, payload);
        ScenarioEntity created = scenarioService.create(entity);
        return ResponseEntity.ok(toMap(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id,
                                                      @RequestBody Map<String, Object> payload) {
        ScenarioEntity saved = scenarioService.update(id, payloadToEntity(payload));
        return ResponseEntity.ok(toMap(saved));
    }

    /**
     * 把前端传的 payload（JSON Object 里 enabledAgents/actionBindings/skills/mcpBindings/planConstraints
     * 都是原生 Array/Object） → ScenarioEntity 的 String 字段里。
     * 否则 Jackson 直接把 JS Array 塞进 Entity 的 String 字段会抛 400 Bad Request。
     */
    private static ScenarioEntity payloadToEntity(Map<String, Object> payload) {
        ScenarioEntity e = new ScenarioEntity();
        applyPayload(e, payload);
        return e;
    }

    private static void applyPayload(ScenarioEntity entity, Map<String, Object> payload) {
        if (payload.get("name") instanceof String s) entity.setName(s);
        if (payload.get("description") instanceof String s) entity.setDescription(s);
        if (payload.get("icon") instanceof String s) entity.setIcon(s);
        if (payload.get("ownerId") instanceof String s) entity.setOwnerId(s);
        if (payload.get("intent") instanceof String s) entity.setIntent(s);
        if (payload.containsKey("isPreset") && payload.get("isPreset") instanceof Boolean b) entity.setIsPreset(b);
        // 以下 5 个字段前端传的是 Array/Object → 我们手动序列化成 JSON 字符串（Entity 字段类型是 String）
        entity.setEnabledAgents(toJsonStr(payload.get("enabledAgents")));
        entity.setActionBindings(toJsonStr(payload.get("actionBindings")));
        entity.setSkills(toJsonStr(payload.get("skills")));
        entity.setMcpBindings(toJsonStr(payload.get("mcpBindings")));
        entity.setPlanConstraints(toJsonStr(payload.get("planConstraints")));
    }

    private static String toJsonStr(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        try { return MAPPER.writeValueAsString(v); }
        catch (Exception e) { return v instanceof List<?> ? "[]" : "{}"; }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        scenarioService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 获取所有可用 Action（来自内置 SubAgent 的 @Export(name=...)）
     * 供前端场景编辑器勾选
     */
    @GetMapping("/actions")
    public ResponseEntity<List<Map<String, Object>>> listActions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ActionRegistry.ActionMeta meta : actionRegistry.getAll().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("actionId", meta.actionId());
            m.put("agentType", meta.agentType());
            m.put("description", meta.description());
            m.put("pre", meta.preConditions());
            m.put("post", meta.postConditions());
            m.put("readOnly", meta.readOnly());
            result.add(m);
        }
        result.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("agentType"))
                .thenComparing(m -> (String) m.get("actionId")));
        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有可用 SubAgent（含 emoji、displayName、description 及下属 Action 列表）
     * 供前端场景编辑器勾选 SubAgent
     */
    @GetMapping("/agents")
    public ResponseEntity<List<Map<String, Object>>> listAgents() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ActionRegistry.AgentMeta meta : actionRegistry.listAllAgents()) {
            result.add(actionRegistry.agentMetaToMap(meta));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 获取 Action 按 agentType 分组
     */
    @GetMapping("/actions/grouped")
    public ResponseEntity<Map<String, List<Map<String, Object>>>> listActionsGrouped() {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (String agentType : actionRegistry.allAgentTypes()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (ActionRegistry.ActionMeta meta : actionRegistry.listByAgentType(agentType)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("actionId", meta.actionId());
                m.put("description", meta.description());
                m.put("pre", meta.preConditions());
                m.put("post", meta.postConditions());
                m.put("readOnly", meta.readOnly());
                list.add(m);
            }
            grouped.put(agentType, list);
        }
        return ResponseEntity.ok(grouped);
    }

    /**
     * 解析 Scenario 实际生效的 Action 列表
     */
    @GetMapping("/{id}/resolved-actions")
    public ResponseEntity<List<Map<String, Object>>> resolvedActions(@PathVariable String id) {
        return scenarioService.findById(id).map(s -> {
            List<Map<String, Object>> result = new ArrayList<>();
            for (ActionRegistry.ActionMeta meta : scenarioService.resolveEnabledActions(s)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("actionId", meta.actionId());
                m.put("agentType", meta.agentType());
                m.put("description", meta.description());
                m.put("pre", meta.preConditions());
                m.put("post", meta.postConditions());
                m.put("readOnly", meta.readOnly());
                result.add(m);
            }
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toMap(ScenarioEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        m.put("icon", s.getIcon());
        m.put("intent", s.getIntent());
        m.put("isPreset", s.getIsPreset());
        m.put("actionBindings", scenarioService.parseActionBindings(s.getActionBindings()));
        m.put("mcpBindings", scenarioService.parseMcpBindings(s.getMcpBindings()));
        m.put("skills", scenarioService.parseSkills(s.getSkills()));
        m.put("enabledAgents", scenarioService.parseEnabledAgents(s.getEnabledAgents()));
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }
}
