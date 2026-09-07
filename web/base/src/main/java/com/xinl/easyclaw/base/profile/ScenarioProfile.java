package com.xinl.easyclaw.base.profile;

/**
 * 场景配置视图（依赖倒置接口）。
 * <p>
 * 由 api 层的 {@code ScenarioEntity implements ScenarioProfile} 完成对接。
 * 全部为 String 标量，无关联、无懒加载，实体侧零改动即可满足。
 *
 * @see RoleProfile RoleProfile（同样的 Lombok getter 对齐要求）
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
}