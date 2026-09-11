package com.xinl.easyclaw.scenario.service;

import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.agent.spi.AgentRegistry;
import com.xinl.easyclaw.base.orchestration.OrchestrationModes;
import com.xinl.easyclaw.base.workflow.WorkflowParseResult;
import com.xinl.easyclaw.base.workflow.WorkflowParser;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.scenario.repository.ScenarioRepository;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import com.xinl.easyclaw.workspace.entity.WorkspaceScenarioEntity;
import com.xinl.easyclaw.workspace.repository.WorkspaceScenarioRepository;
import com.xinl.easyclaw.config.SystemHomePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 场景管理服务
 * <p>
 * 场景 CRUD + 工作区激活/停用。激活时立即重建该工作区的 Agent，
 * 使场景提示词/编排工作流注入 system prompt；WorkspaceManager 构建
 * Agent 时也会从 {@code workspace_scenarios} 读取激活场景（重启后仍生效）。
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    private final ScenarioRepository scenarioRepo;
    private final WorkspaceScenarioRepository activationRepo;
    private final WorkspaceManager workspaceManager;
    private final AgentRegistry agentRegistry;

    public ScenarioService(ScenarioRepository scenarioRepo,
                           WorkspaceScenarioRepository activationRepo,
                           WorkspaceManager workspaceManager,
                           AgentRegistry agentRegistry) {
        this.scenarioRepo = scenarioRepo;
        this.activationRepo = activationRepo;
        this.workspaceManager = workspaceManager;
        this.agentRegistry = agentRegistry;
    }

    public List<ScenarioEntity> findAll() {
        return scenarioRepo.findAll();
    }

    public List<ScenarioEntity> findActive() {
        return scenarioRepo.findByActiveTrue();
    }

    @Transactional
    public ScenarioEntity create(ScenarioEntity scenario) {
        if (scenario.getName() == null || scenario.getName().isBlank()) {
            throw new IllegalArgumentException("场景名称不能为空");
        }
        String name = scenario.getName().trim();
        if (scenarioRepo.existsByName(name)) {
            throw new IllegalArgumentException("场景名称已存在: " + name);
        }
        if (scenario.getMode() == null || scenario.getMode().isBlank()) {
            scenario.setMode("single");
        }
        validateWorkflow(scenario);
        scenario.setId(null);
        scenario.setName(name);
        scenario.setBuiltin(false);
        // 与 update 保持同一套归一规则，避免新建走出 "" / null 两种「未绑定」表示
        scenario.setSkills(blankToNull(scenario.getSkills()));
        scenario.setSubagents(blankToNull(scenario.getSubagents()));
        scenario.setMcpServices(blankToNull(scenario.getMcpServices()));
        scenario.setCapabilityTier(blankToNull(scenario.getCapabilityTier()));
        scenario.setRoleName(blankToNull(scenario.getRoleName()));
        ScenarioEntity saved = scenarioRepo.save(scenario);
        log.info("创建场景: name={}, mode={}", name, saved.getMode());
        return saved;
    }

    @Transactional
    public ScenarioEntity update(Long id, ScenarioEntity patch) {
        return scenarioRepo.findById(id)
                .map(existing -> {
                    if (patch.getDisplayName() != null) existing.setDisplayName(patch.getDisplayName());
                    if (patch.getIcon() != null) existing.setIcon(patch.getIcon());
                    if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
                    if (patch.getMode() != null) existing.setMode(patch.getMode());
                    if (patch.getSystemPrompt() != null) existing.setSystemPrompt(patch.getSystemPrompt());
                    if (patch.getWorkflow() != null) existing.setWorkflow(patch.getWorkflow());
                    if (patch.getActive() != null) existing.setActive(patch.getActive());
                    // 能力绑定四列：与上面字段不同，这里用 "" 表示「清空绑定」。
                    // 若沿用 null 跳过的写法，用户在界面上取消全部勾选后将无法解绑。
                    if (patch.getSkills() != null) existing.setSkills(blankToNull(patch.getSkills()));
                    if (patch.getSubagents() != null) existing.setSubagents(blankToNull(patch.getSubagents()));
                    if (patch.getMcpServices() != null) existing.setMcpServices(blankToNull(patch.getMcpServices()));
                    if (patch.getCapabilityTier() != null) {
                        existing.setCapabilityTier(blankToNull(patch.getCapabilityTier()));
                    }
                    // 绑定智能体同属「"" 表示解绑」语义：用户在下拉里选回「默认主控」
                    // 时前端传 ""，必须能真正清空，否则智能体一旦绑定就摘不掉
                    if (patch.getRoleName() != null) {
                        existing.setRoleName(blankToNull(patch.getRoleName()));
                    }
                    validateWorkflow(existing);
                    ScenarioEntity updated = scenarioRepo.save(existing);
                    log.info("更新场景: id={}, name={}", id, updated.getName());
                    // 场景内容变化后，刷新所有激活了该场景的工作区
                    for (WorkspaceScenarioEntity act : activationRepo.findByScenarioId(id)) {
                        safeRebuild(act.getWorkspaceId());
                    }
                    return updated;
                })
                .orElseThrow(() -> new IllegalArgumentException("场景不存在: id=" + id));
    }

    @Transactional
    public void delete(Long id) {
        scenarioRepo.findById(id).ifPresent(s -> {
            List<WorkspaceScenarioEntity> activations = activationRepo.findByScenarioId(id);
            // 先删激活关系再删场景，最后统一重建（清掉 augment）
            activationRepo.deleteAll(activations);
            scenarioRepo.delete(s);
            log.info("删除场景: id={}, name={}，联动停用 {} 个工作区", id, s.getName(), activations.size());
            for (WorkspaceScenarioEntity act : activations) {
                safeRebuild(act.getWorkspaceId());
            }
        });
    }

    /**
     * 激活场景（每工作区一个，重复激活即切换），立即重建 Agent 生效
     */
    @Transactional
    public ScenarioEntity activate(String workspaceId, Long scenarioId) {
        ScenarioEntity scenario = scenarioRepo.findById(scenarioId)
                .filter(ScenarioEntity::getActive)
                .orElseThrow(() -> new IllegalArgumentException("场景不存在或已停用: id=" + scenarioId));
        WorkspaceScenarioEntity act = activationRepo.findByWorkspaceId(workspaceId)
                .orElseGet(() -> WorkspaceScenarioEntity.builder()
                        .workspaceId(workspaceId)
                        .build());
        act.setScenarioId(scenarioId);
        activationRepo.save(act);
        log.info("激活场景: workspace={}, scenario={}", workspaceId, scenario.getName());
        safeRebuild(workspaceId);
        return scenario;
    }

    /**
     * 按场景标识名激活（供「创建工作区时绑定默认场景」等场景使用）。
     * <p>
     * 与 {@link #activate(String, Long)} 的区别：调用方只知道稳定的业务标识
     * （如内置的 {@code general-coding}），不依赖自增主键 —— 主键在不同环境
     * 的种子顺序下并不稳定。
     *
     * @return 实际激活的场景；场景不存在或已停用时返回 {@link Optional#empty()}
     */
    @Transactional
    public Optional<ScenarioEntity> activateByName(String workspaceId, String scenarioName) {
        if (scenarioName == null || scenarioName.isBlank()) {
            return Optional.empty();
        }
        return scenarioRepo.findByName(scenarioName.trim())
                .filter(s -> Boolean.TRUE.equals(s.getActive()))
                .map(s -> activate(workspaceId, s.getId()));
    }

    /**
     * 按稳定标识名查询「存在且已启用」的场景。
     * <p>供「工作区绑定场景前校验类型是否匹配」使用：调用方需要在真正激活前同时拿到
     * 场景的 {@code mode} 做工作区类型强一致校验，故单独暴露，避免激活与校验各查一次。
     */
    @Transactional(readOnly = true)
    public Optional<ScenarioEntity> findActiveByName(String scenarioName) {
        if (scenarioName == null || scenarioName.isBlank()) {
            return Optional.empty();
        }
        return scenarioRepo.findByName(scenarioName.trim())
                .filter(s -> Boolean.TRUE.equals(s.getActive()));
    }

    /**
     * 停用工作区场景（回到默认主智能体），立即重建 Agent
     */
    @Transactional
    public void deactivate(String workspaceId) {
        if (activationRepo.findByWorkspaceId(workspaceId).isPresent()) {
            activationRepo.deleteByWorkspaceId(workspaceId);
            log.info("停用场景: workspace={}", workspaceId);
            safeRebuild(workspaceId);
        }
    }

    /**
     * 查询工作区当前激活的场景（未激活返回 null）
     */
    public ScenarioEntity activeScenario(String workspaceId) {
        return activationRepo.findByWorkspaceId(workspaceId)
                .flatMap(act -> scenarioRepo.findById(act.getScenarioId()))
                .filter(ScenarioEntity::getActive)
                .orElse(null);
    }

    /**
     * 可用于编排的子 Agent 名单
     * <p>
     * 口径与 {@code SubagentLoader.loadMerged} 完全一致：<b>来源是 SPI 注册表</b>，
     * 并排除主控自身（{@code main} 不可被派遣，否则开出递归入口）。
     * <p>
     * 历史实现扫描 {@code subagents/*.md} 目录，随 {@code .md} 链路一并下线。
     * 保留 {@code workspaceId} 参数是为了不破坏既有前端调用契约；SPI 名单不区分
     * 工作区，故该参数当前不参与计算。
     *
     * @param workspaceId 工作区 ID（当前不影响结果，保留以兼容既有调用）
     */
    public List<Map<String, String>> availableSubagents(String workspaceId) {
        List<Map<String, String>> result = new ArrayList<>();
        for (String agentId : agentRegistry.agentIds()) {
            if (SubagentLoader.MAIN_AGENT_ID.equals(agentId)) {
                continue;
            }
            result.add(Map.of("name", agentId, "scope", "builtin"));
        }
        return result;
    }

    /**
     * 全部已注册智能体清单（含 main 主控），供前端智能体选择下拉使用。
     * <p>
     * 与 {@link #availableSubagents(String)} 的区别：后者用于「编排可派成员」，
     * 刻意排除 main；本方法用于场景/工作流的「主控 + 步骤执行体」选择，
     * 需要含 main 且带展示名。单个智能体 profile 取值异常不拖垮整个列表。
     *
     * @return 每项含 {@code agentId}、{@code displayName}、{@code description}、{@code icon}、{@code main}
     */
    public List<Map<String, String>> allAgents() {
        List<Map<String, String>> result = new ArrayList<>();
        for (var agent : agentRegistry.all()) {
            String agentId = agent.agentId();
            String displayName = agentId;
            String description = "";
            String icon = "";
            try {
                var profile = agent.profile();
                if (profile != null) {
                    if (profile.displayName() != null && !profile.displayName().isBlank()) {
                        displayName = profile.displayName();
                    }
                    description = profile.description() == null ? "" : profile.description();
                    icon = profile.icon() == null ? "" : profile.icon();
                }
            } catch (Exception e) {
                log.warn("[Agent] 读取智能体 {} 的 profile 失败，下拉仅展示 agentId", agentId, e);
            }
            result.add(Map.of(
                    "agentId", agentId,
                    "displayName", displayName,
                    "description", description,
                    "icon", icon,
                    "main", String.valueOf(SubagentLoader.MAIN_AGENT_ID.equals(agentId))));
        }
        return result;
    }

    /** 空白字符串归一为 null：让「未绑定」在库里只有一种表示，避免 "" 与 null 两套判断 */
    private static String blankToNull(String raw) {
        return (raw == null || raw.isBlank()) ? null : raw;
    }

    /**
     * 校验工作流：编排模式必须带至少一个合法步骤，且 JSON 必须通过 schema 校验。
     * <p>
     * 修复此前「JSON 语法错误也报成 subagent 不能为空」的误导性文案：
     * 现在语法错误、未知字段、类型错误都会带下标精确报出。
     * <p>
     * 「是否需要 workflow」由 {@link OrchestrationModes#isOrchestrated} 判定而非
     * 硬编码比对 team —— 新增编排型模式时本方法自动生效。
     */
    private void validateWorkflow(ScenarioEntity scenario) {
        boolean orchestrated = OrchestrationModes.isOrchestrated(scenario.getMode());
        WorkflowParseResult parsed = WorkflowParser.parse(scenario.getWorkflow());

        if (!parsed.ok()) {
            throw new IllegalArgumentException("工作流配置非法：" + parsed.errorMessage());
        }
        if (orchestrated && !parsed.hasSteps()) {
            throw new IllegalArgumentException(
                    OrchestrationModes.displayNameOf(scenario.getMode()) + "模式需要至少一个工作流步骤");
        }
        for (String warning : parsed.warnings()) {
            log.warn("场景[{}] 工作流告警: {}", scenario.getName(), warning);
        }
    }

    private void safeRebuild(String workspaceId) {
        try {
            workspaceManager.rebuildAgent(workspaceId);
        } catch (Exception e) {
            log.warn("重建 Agent 失败（工作区可能未加载）: workspace={}, {}", workspaceId, e.getMessage());
        }
    }
}
