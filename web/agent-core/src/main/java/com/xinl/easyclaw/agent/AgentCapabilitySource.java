package com.xinl.easyclaw.agent;

import java.util.Collection;
import java.util.List;

/**
 * 智能体级能力绑定的读取入口。
 * <p>
 * <b>为什么要有这个接口</b>：能力绑定存在 api 层的库里（{@code agent_capabilities} 表），
 * 而消费它的两处逻辑分别在 agent-core（子 Agent 工具白名单）与 api（主控 toolkit 装配）。
 * agent-core 不能反向依赖 api 的 repository，所以在此声明契约、由 api 提供实现。
 * <p>
 * <b>为什么是可选依赖</b>：{@code SubagentLoader} 的既有测试用 {@code new} 直接构造，
 * 没有 Spring 上下文；实现为 null 时必须等价于「没有任何 agent 级绑定」，
 * 让行为完全回退到改造前。
 */
public interface AgentCapabilitySource {

    /** 能力类型：MCP 服务（内置能力实例与外部 MCP 服务同受此约束） */
    String KIND_MCP = "mcp";

    /**
     * 该智能体已绑定且启用的 MCP 服务名。
     *
     * @param agentId 智能体标识（主控为 {@code main}，子 Agent 为其声明名）
     * @return 服务名列表；无绑定返回空列表，<b>不返回 null</b>
     */
    List<String> mcpServiceNames(String agentId);

    /**
     * 多个智能体的 MCP 绑定<b>并集</b>——主控 toolkit 用它决定注册哪些 MCP 服务的工具。
     * <p>
     * 必须是并集：子 Agent 继承主控 toolkit，父侧少一个服务，子侧的绑定就成了空壳。
     */
    List<String> mcpServiceNamesFor(Collection<String> agentIds);

    /**
     * 该智能体绑定的 MCP 服务展开成的<b>工具名</b>（供子 Agent 白名单交集收窄）。
     * <p>
     * 展开由 api 层完成：agent-core 不访问 MCP 仓库，也就无法自行把服务名换成工具名。
     */
    List<String> mcpToolNames(String agentId);
}
