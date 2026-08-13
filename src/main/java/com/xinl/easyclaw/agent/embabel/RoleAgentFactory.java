package com.xinl.easyclaw.agent.embabel;

import com.embabel.agent.api.tool.Subagent;
import com.embabel.agent.core.AgentPlatform;
import com.xinl.easyclaw.agent.embabel.subagent.CodeAgent;
import com.xinl.easyclaw.agent.embabel.subagent.FileAgent;
import com.xinl.easyclaw.agent.embabel.subagent.WebAgent;
import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import com.xinl.easyclaw.role.service.RoleManagementService;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import com.xinl.easyclaw.agent.embabel.domain.UserRequest;
import com.embabel.agent.domain.io.UserInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色 → Agent 实例化工厂
 * <p>
 * 职责：根据 AgentRoleEntity DB 记录动态创建 Agent 实例。
 * <p>
 * 映射规则：
 * <pre>
 * AgentRoleEntity
 *   ├─ systemPrompt  → Agent 的 systemPrompt（withSystemPrompt）
 *   ├─ toolGroups    → 要挂载的工具组（CoreToolGroups 字符串 / McpToolGroup）
 *   ├─ subagents     → 要委托的子 Agent（动态注入 Subagent 工具）
 *   └─ permissions   → 权限规则（permission_rules 表查询）
 * </pre>
 * <p>
 * 当前阶段：先支持内置子 Agent 的静态注册，DB 驱动的动态注册后续补。
 */
@Component
public class RoleAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(RoleAgentFactory.class);

    private final AgentPlatform platform;
    private final RoleManagementService roleService;

    private final Map<String, com.embabel.agent.core.Agent> agentCache = new ConcurrentHashMap<>();

    public RoleAgentFactory(AgentPlatform platform, RoleManagementService roleService) {
        this.platform = platform;
        this.roleService = roleService;
        // 预注册 Orchestrator + 3 个子 Agent
        registerBuiltinAgents();
    }

    private void registerBuiltinAgents() {
        try {
            platform.deploy(platform.agents()); // Embabel auto-discovers @Agent beans
            log.info("已注册 Embabel AgentPlatform 自动发现的 Agent Bean");
        } catch (Exception e) {
            log.info("AgentPlatform 自动发现: {}", e.getMessage());
        }
    }

    /**
     * 根据角色名获取对应的 Agent，不存在则回退到 OrchestratorAgent。
     */
    public com.embabel.agent.core.Agent resolveAgent(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return findByClass(OrchestratorAgent.class);
        }
        return switch (roleName.toLowerCase()) {
            case "file", "file-agent" -> findByClass(FileAgent.class);
            case "web", "web-agent" -> findByClass(WebAgent.class);
            case "code", "code-agent" -> findByClass(CodeAgent.class);
            default -> findByClass(OrchestratorAgent.class);
        };
    }

    private com.embabel.agent.core.Agent findByClass(Class<?> clazz) {
        return platform.agents().stream()
                .filter(a -> a.getClass().equals(clazz))
                .findFirst()
                .orElse(null);
    }

    /**
     * 返回角色可用的子 Agent 引用列表（用于 Subagent 工具注入）。
     */
    public List<com.embabel.agent.api.tool.Tool> getSubagentTools(com.embabel.agent.core.Agent agent) {
        return List.of(
                Subagent.ofClass(FileAgent.class).consuming(UserInput.class),
                Subagent.ofClass(WebAgent.class).consuming(UserInput.class),
                Subagent.ofClass(CodeAgent.class).consuming(UserInput.class)
        );
    }
}
