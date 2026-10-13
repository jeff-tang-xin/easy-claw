package com.xinl.easyclaw.mcp.type;

import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import io.agentscope.core.tool.AgentTool;

import java.util.List;
import java.util.Map;

/**
 * 内置 MCP 能力类型 —— 「本地操作能力」进入 MCP 体系的注册契约。
 * <p>
 * <b>为什么需要它</b>：内置服务此前靠服务名字符串识别（历史实现是
 * {@code BUILTIN_DB_SERVICE.equals(entity.getName())}），后果是「内置」等于「系统单例」：
 * 用户既建不出第二个数据库服务，也无法把能力绑定到指定智能体，任何改名都静默失效。
 * 本接口把「内置」从名字提升为<b>类型</b>：一个类型可以被实例化成 N 个服务，
 * 每个实例自带该类型的工具面 + 自己的配置（如锁定某个 hub 下发的数据库）。
 * <p>
 * <b>为什么放 api 层而不是 base</b>：{@link #toolsFor} 返回
 * {@link AgentTool}（agentscope 类型）且实现要注入 {@code DbConnectionService}
 * 等 api 层服务；{@code web/base} 是纯声明契约层（不依赖 Spring、不依赖 agentscope），
 * 下沉进去会破坏那条边界。
 * <p>
 * 实现类标 {@code @Component} 即自动注册（由 {@link BuiltinMcpRegistry} 收集）。
 */
public interface BuiltinMcpType {

    /**
     * 类型标识，落到 {@code mcp_services.mcp_type} 列。
     * 小写字母 + 连字符（如 {@code db}、{@code ops-server}），全局唯一。
     */
    String typeId();

    /** 人类可读名称，供前端「创建 MCP 服务」的类型下拉展示。 */
    String displayName();

    /**
     * 实例配置表单描述：前端据此渲染配置项，<b>不硬编码字段名</b>。
     * <p>
     * 每项形如 {@code {key, label, type, required, optionsSource, description}}；
     * {@code optionsSource} 指明候选源（如 {@code hub-db-connections}），
     * 由 Controller 层补齐实际选项——类型本身不查库。
     */
    List<Map<String, Object>> configSchema();

    /**
     * 按<b>实例配置</b>产出工具面（进程内直调本地服务，不起外部进程）。
     * <p>
     * 必须可从配置静态推导：调用时机有两处——① 实例激活（connect）时装配进工具缓存；
     * ② 场景/智能体绑定展开工具名时，<b>实例尚未连接也要能拿到清单</b>，
     * 否则白名单展开为空会让子智能体静默失能。
     *
     * @param instance 服务实例（读 {@code implementationConfig} 里的类型专属配置）
     */
    List<AgentTool> toolsFor(McpServiceEntity instance);
}
