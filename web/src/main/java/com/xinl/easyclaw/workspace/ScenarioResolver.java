package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.scenario.repository.ScenarioRepository;
import com.xinl.easyclaw.workspace.repository.WorkspaceScenarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工作区激活场景的查询入口。
 * <p>
 * 单独成类是为了打断依赖环：{@link WorkspaceAgentBuilder} 构建 Agent 时需要读
 * 激活场景来拼装编排提示词，而 {@code WorkspaceManager} 又依赖 builder。若把
 * 场景查询留在 manager 里，两者会互相注入。
 * <p>
 * <b>失败降级</b>：场景查询异常按「无场景」处理 —— 场景是增强能力，
 * 表结构异常或数据缺失不应让工作区整体无法加载。
 */
@Component
public class ScenarioResolver {

    private static final Logger log = LoggerFactory.getLogger(ScenarioResolver.class);

    private final ScenarioRepository scenarioRepository;
    private final WorkspaceScenarioRepository workspaceScenarioRepository;

    public ScenarioResolver(ScenarioRepository scenarioRepository,
                            WorkspaceScenarioRepository workspaceScenarioRepository) {
        this.scenarioRepository = scenarioRepository;
        this.workspaceScenarioRepository = workspaceScenarioRepository;
    }

    /**
     * 查询工作区当前激活的场景（未激活 / 已停用 / 已删除时返回 null）
     */
    public ScenarioEntity activeScenario(String workspaceId) {
        try {
            return workspaceScenarioRepository.findByWorkspaceId(workspaceId)
                    .flatMap(act -> scenarioRepository.findById(act.getScenarioId()))
                    .filter(s -> Boolean.TRUE.equals(s.getActive()))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("查询激活场景失败（忽略，按无场景构建）: workspace={}, {}", workspaceId, e.getMessage());
            return null;
        }
    }

    /**
     * 当前激活场景的工作流 JSON（非 team 模式或未激活时返回 null）
     * <p>
     * 供编排审计使用：把「计划」与主智能体自报的「实际执行」做比对。
     */
    public String activeWorkflowJson(String workspaceId) {
        ScenarioEntity scenario = activeScenario(workspaceId);
        if (scenario == null || !"team".equals(scenario.getMode())) {
            return null;
        }
        return scenario.getWorkflow();
    }
}
