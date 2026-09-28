package com.xinl.easyclaw.base.profile;

/**
 * 场景配置视图（依赖倒置接口）。
 * <p>
 * 由 api 层的 {@code ScenarioEntity implements ScenarioProfile} 完成对接。
 * 全部为 String 标量，无关联、无懒加载，实体侧零改动即可满足。
 */
public interface ScenarioProfile {

    /** 场景英文标识，如 {@code "team-dev"} */
    String getName();

    /** 场景展示名 */
    String getDisplayName();

    /** 图标 */
    String getIcon();

    /** 场景描述 */
    String getDescription();

    /** 场景级系统提示词 */
    String getSystemPrompt();

    /**
     * 调度模式，取值 {@code "single"} 或 {@code "team"}。
     * <p><b>存量字面量，不可改名</b>（SQLite ddl-auto:update 不迁移历史值）。
     */
    String getMode();

    /** 工作流 JSON，仅 team 模式使用；格式见 {@code WorkflowParser} */
    String getWorkflow();

    /** 绑定的 skill 名列表（JSON 数组文本）；解析见 {@code ScenarioBinding} */
    String getSkills();

    /** 绑定的子智能体名列表（JSON 数组文本） */
    String getSubagents();

    /** 绑定的 MCP 服务名列表（JSON 数组文本），硬约束 */
    String getMcpServices();

    /** 能力档位；空/空白表示未显式配置，此时不得裁剪工具 */
    String getCapabilityTier();

    /**
     * 绑定的主智能体标识（语义 = SPI {@link com.xinl.easyclaw.base.agent.EasyClawAgent#agentId()}）。
     * <p>
     * 物理列名沿用历史的 {@code role_name}：SQLite {@code ddl-auto:update} 不做列名迁移，
     * 改名会丢存量绑定，故列名保留、语义已切换为 agentId。
     * <p>
     * single 模式：本场景要用哪个智能体；空 = 回退默认的 {@code main}（AI-CLAW）。
     * <p>
     * team 模式：指协调者智能体，其余成员由 {@code getWorkflow()} 的步骤定义。
     * <p>
     * 若绑定了已下线（SPI 未注册）的标识（如历史的 {@code creative-writer}），
     * 由各编排器解析时回退 {@code main} 并告警。
     */
    String getRoleName();
}