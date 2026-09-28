package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.base.orchestration.OrchestrationModes;
import com.xinl.easyclaw.scenario.ScenarioBinding;
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
     * 当前激活场景的能力绑定（未激活时返回 {@link ScenarioBinding#EMPTY} = 不限制）。
     * <p>供 Agent 装配时决定 toolkit 白名单、子 Agent 隔离与提示词推荐。
     */
    public ScenarioBinding activeBinding(String workspaceId) {
        return ScenarioBinding.from(activeScenario(workspaceId));
    }

    /**
     * 当前激活场景的工作流 JSON（非编排模式或未激活时返回 null）
     * <p>
     * 供编排审计使用：把「计划」与主智能体自报的「实际执行」做比对。
     */
    public String activeWorkflowJson(String workspaceId) {
        ScenarioEntity scenario = activeScenario(workspaceId);
        if (scenario == null || !OrchestrationModes.isOrchestrated(scenario.getMode())) {
            return null;
        }
        return scenario.getWorkflow();
    }

    /**
     * 当前工作区的场景是否启用「工具白名单（永久授权）」机制。
     * <p>
     * <b>场景决定机制开关</b>：运维（ops）场景不启用——remote_shell 每次调用都必须
     * 弹用户确认，回合授权（allowTurn）与永久授权（allowPermanently）一律不生效，
     * 存量授权规则也不会摘掉 system ASK 规则（见 WorkspaceAgentBuilder.buildPermissionContext
     * 与 AgentService.syncPermissionRules 的对应分支）。其余场景维持原行为（启用）。
     */
    public boolean whitelistEnabled(String workspaceId) {
        ScenarioEntity scenario = activeScenario(workspaceId);
        if (scenario == null) {
            return true;
        }
        // 模式自声明（AgentOrchestrator.whitelistEnabled，SPI 发现）；未注册 mode 维持默认行为
        return com.xinl.easyclaw.base.orchestration.OrchestrationModes.find(scenario.getMode())
                .map(com.xinl.easyclaw.base.orchestration.AgentOrchestrator::whitelistEnabled)
                .orElse(true);
    }
}
