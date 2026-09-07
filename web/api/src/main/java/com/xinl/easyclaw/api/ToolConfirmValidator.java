package com.xinl.easyclaw.api;

import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.tool.service.ToolRegistryService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具确认请求的工具名校验（code-review Finding 6 配套）。
 * <p>
 * 为什么必须校验：{@code action=always} 会走 {@code allowPermanently}，把工具名写成
 * 绑定 workspace 的永久免确认规则。若放过任意字符串，调用方就能预埋规则——
 * 等到未来真出现同名工具（或 MCP 服务提供同名工具）时，它将默认免确认执行。
 * <p>
 * 为什么不能只查 {@link ToolRegistryService}：MCP 工具由外部服务动态提供，
 * 不落 {@code tool_definitions} 表，仅靠注册表会把合法的 MCP 确认全部拒掉。
 * 因此合法来源取并集：
 * <ul>
 *   <li>内置 / DB 登记的工具（注册表已知）</li>
 *   <li><b>该会话当前正挂起等待确认的工具名</b> —— 这批名字是后端自己发出去的，
 *       天然可信，且覆盖了 MCP 动态工具；同时它把授权范围收窄到「用户真被问到的工具」。</li>
 * </ul>
 */
@Component
public class ToolConfirmValidator {

    private final AgentService agentService;
    private final ToolRegistryService toolRegistryService;

    public ToolConfirmValidator(AgentService agentService, ToolRegistryService toolRegistryService) {
        this.agentService = agentService;
        this.toolRegistryService = toolRegistryService;
    }

    /**
     * 挑出不可接受的工具名。
     *
     * @return 非法工具名列表；为空表示全部合法
     */
    public List<String> rejectUnknown(String sessionId, Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        Set<String> pending = agentService.pendingConfirmInfo(sessionId).stream()
                .map(m -> String.valueOf(m.get("name")))
                .collect(Collectors.toSet());
        return toolNames.stream()
                .filter(n -> !isAcceptable(n, pending))
                .toList();
    }

    private boolean isAcceptable(String name, Set<String> pending) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return pending.contains(name) || toolRegistryService.isRegistered(name);
    }
}
