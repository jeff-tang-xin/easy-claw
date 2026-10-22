package com.xinl.easyclaw.agent.team;

import com.xinl.easyclaw.workspace.WorkspaceAgentBuilder;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * team 成员 Agent 工厂：把成员构建委托给 {@link WorkspaceAgentBuilder#buildMember}。
 *
 * <p><b>独立组件的原因</b>：{@link TeamRuntimeService} 需要在 spawn 回调里构建成员
 * Agent，而成员装配（模型/文件系统沙箱/状态目录）是 workspace 装配逻辑——若由
 * AgentService 兼任工厂会引入 AgentService ↔ TeamRuntimeService 循环依赖
 * （AgentService 也要注入 TeamRuntimeService 做 lead 装配）。拆出本组件后依赖链为
 * TeamRuntimeService → 本组件 → WorkspaceAgentBuilder，无环。
 */
@Component
public class TeamMemberAgentFactory implements TeamRuntimeService.MemberAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(TeamMemberAgentFactory.class);

    private final WorkspaceAgentBuilder workspaceAgentBuilder;
    private final WorkspaceManager workspaceManager;

    public TeamMemberAgentFactory(WorkspaceAgentBuilder workspaceAgentBuilder,
                                  WorkspaceManager workspaceManager) {
        this.workspaceAgentBuilder = workspaceAgentBuilder;
        this.workspaceManager = workspaceManager;
    }

    @Override
    public HarnessAgent create(SubagentDeclaration declaration, String memberSessionId,
                               io.agentscope.harness.agent.team.TeamContext context,
                               EasyClawTeamClient client) {
        // TeamContext 的 namespace 即 workspaceId（TeamRuntimeService.startTeam 约定）
        String workspaceId = context.resolvedNamespace();
        WorkspaceContext ws = workspaceManager.getWorkspace(workspaceId);
        if (ws == null) {
            throw new IllegalStateException("成员构建被拒：工作区不存在 " + workspaceId);
        }
        HarnessAgent member = workspaceAgentBuilder.buildMember(
                workspaceId, declaration, ws.getPath(), ws.getPath().resolve(".easyClaw"),
                client, context, memberSessionId);
        log.info("[team] 成员 Agent 已构建: member={}, session={}",
                declaration.getName(), memberSessionId);
        return member;
    }
}
