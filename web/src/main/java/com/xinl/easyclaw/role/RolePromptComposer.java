package com.xinl.easyclaw.role;

import com.xinl.easyclaw.role.entity.AgentRoleEntity;

/**
 * 角色系统提示词组装器
 * <p>
 * 把 {@link AgentRoleEntity} 的人格三要素（role / goal / backstory）渲染成一段
 * system prompt 片段。<b>这是"角色决定系统提示词"的唯一实现点</b>——在此之前，
 * 这三个字段只在 CRUD 里被读写，从未进入过任何 prompt（历史遗留：角色实体只被
 * 用来取 model 和 displayName）。
 * <p>
 * 设计取舍：
 * <ul>
 *   <li><b>角色 = 角色定义 + LLM</b>：本类只负责前半段（人格三要素渲染）；后半段
 *       （该角色专属的 model / baseUrl / apiKey）由
 *       {@code AgentFactory.resolveRoleModel} 处理。两者共同构成"你是什么"。</li>
 *   <li><b>为什么是"片段"而不是"整段 system prompt"</b>：工具协议与安全规范属于
 *       基座层，与角色人格正交。角色只描述"你是谁"，不该重抄那套协议。</li>
 *   <li><b>为什么不再声明"本设定不豁免工具规范"</b>：层级效力由基座的「分层约定」
 *       统一裁决（安全规范 &gt; 场景边界 &gt; 角色倾向 &gt; 用户偏好）。角色层替基座
 *       立法既越权，又会在每个角色片段里重复一遍同样的话。</li>
 *   <li><b>为什么允许字段全空</b>：角色可以只用来指定 model（现有 main 角色的
 *       典型用法），此时返回 null 表示"无人格覆盖"，调用方原样使用基础提示词。</li>
 * </ul>
 */
public final class RolePromptComposer {

    private RolePromptComposer() {
    }

    /**
     * 渲染角色人格片段。
     *
     * @param role 角色实体，可为 null
     * @return 提示词片段；角色为 null 或三要素全空时返回 {@code null}
     */
    public static String compose(AgentRoleEntity role) {
        if (role == null) {
            return null;
        }
        boolean hasPersona = notBlank(role.getRole())
                || notBlank(role.getGoal())
                || notBlank(role.getBackstory());
        if (!hasPersona) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 🎭 你的角色：").append(displayNameOf(role)).append("\n");
        if (notBlank(role.getRole())) {
            sb.append("**角色定位**：").append(role.getRole().trim()).append("\n");
        }
        if (notBlank(role.getGoal())) {
            sb.append("**你的目标**：").append(role.getGoal().trim()).append("\n");
        }
        if (notBlank(role.getBackstory())) {
            sb.append("**背景设定**：\n").append(role.getBackstory().trim()).append("\n");
        }
        sb.append("\n以上角色设定决定你的专业视角、判断标准与说话方式，在整个会话中保持一致。");
        return sb.toString();
    }

    /**
     * 角色展示名：优先 displayName，回退英文标识。
     */
    public static String displayNameOf(AgentRoleEntity role) {
        if (role == null) {
            return "";
        }
        return notBlank(role.getDisplayName()) ? role.getDisplayName().trim() : role.getName();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
