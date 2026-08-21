package com.xinl.easyclaw.scenario.service;

import com.xinl.easyclaw.agent.orchestrator.OrchestrationPromptBuilder;
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

    public ScenarioService(ScenarioRepository scenarioRepo,
                           WorkspaceScenarioRepository activationRepo,
                           WorkspaceManager workspaceManager) {
        this.scenarioRepo = scenarioRepo;
        this.activationRepo = activationRepo;
        this.workspaceManager = workspaceManager;
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
     * 可用于编排的子 Agent 名单（全局目录 ~/.easyClaw/subagents 下的声明文件名）
     */
    public List<Map<String, String>> availableSubagents() {
        List<Map<String, String>> result = new ArrayList<>();
        Path dir = SystemHomePaths.globalSubagentsDir();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> result.add(Map.of(
                            "name", p.getFileName().toString().replaceAll("\\.md$", ""))));
        } catch (IOException e) {
            log.warn("读取全局子 Agent 目录失败: {}", e.getMessage());
        }
        return result;
    }

    /** team 模式必须带至少一个合法步骤（single 模式忽略 workflow） */
    private void validateWorkflow(ScenarioEntity scenario) {
        if (!"team".equals(scenario.getMode())) {
            return;
        }
        List<OrchestrationPromptBuilder.WorkflowStep> steps =
                OrchestrationPromptBuilder.parseWorkflow(scenario.getWorkflow());
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("team 模式需要至少一个工作流步骤（subagent 不能为空）");
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
