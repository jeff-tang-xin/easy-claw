package com.xinl.easyclaw.base.agent;

/**
 * 所有智能体共同实现的 SPI 契约。
 * <p>
 * 7 个平级智能体（AI-CLAW/coder/reviewer/planner/researcher/code-expert/file-expert）
 * 各自实现本接口，在 {@code META-INF/services} 注册，由 agent-core 的 SPI 装配器
 * 按 agentId 发现并加载。
 * <p>
 * 新增智能体 = 新建一个 Maven 模块 + 实现本接口 + 注册 services 文件。
 * 公共代码（agent-core / api）零改动，这就是「简化后续开发」。
 *
 * @see com.xinl.easyclaw.base.agent.AgentProfile
 * @see com.xinl.easyclaw.base.agent.AgentContext
 * @see com.xinl.easyclaw.base.agent.PromptContribution
 * @see com.xinl.easyclaw.base.agent.ToolPolicy
 * @see com.xinl.easyclaw.base.agent.SkillPolicy
 * @see com.xinl.easyclaw.base.agent.ModelPreference
 * @see com.xinl.easyclaw.base.agent.DispatchPolicy
 */
public interface EasyClawAgent {

    /** 唯一标识，如 {@code "main"}、{@code "coder"}、{@code "reviewer"} */
    String agentId();

    /** 智能体展示信息：显示名、描述、图标（可选） */
    AgentProfile profile();

    /**
     * 贡献当前智能体的人格与职责提示词片段。
     * <p>
     * 基座层（工具协议、安全规范、分层约定）由 agent-core 统一注入，本方法只负责
     * 「你是谁」部分——即角色人格（role/goal/backstory）与职责描述。
     * 返回 null 表示「无人格覆盖」，调用方使用基础提示词。
     */
    PromptContribution prompt(AgentContext ctx);

    /** 工具授权策略：白名单 + 别名映射 */
    ToolPolicy toolPolicy();

    /** 技能授权策略 */
    SkillPolicy skillPolicy();

    /** 模型偏好：优先使用哪个模型，回退策略 */
    ModelPreference modelPreference();

    /** 主控迭代步数下限（{@code stepFloor}） */
    int stepFloor();

    /**
     * 子智能体派遣策略。
     * <p>
     * 所有 7 个智能体都能派遣子智能体（派遣是通用能力），但可以限制可派谁、
     * 并发上限、超时分档。返回 {@link DispatchPolicy#DISABLED} 表示禁止派遣。
     */
    DispatchPolicy dispatchPolicy();
}