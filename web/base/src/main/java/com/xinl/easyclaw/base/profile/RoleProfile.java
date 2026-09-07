package com.xinl.easyclaw.base.profile;

/**
 * 角色人格视图（依赖倒置接口）。
 * <p>
 * base 层不认识 JPA 实体，但需要读取角色的人格三要素来渲染提示词。
 * 由 api 层的 {@code AgentRoleEntity implements RoleProfile} 完成对接，
 * 依赖方向恒为 api → base。
 * <p>
 * <b>实现方注意</b>：{@code AgentRoleEntity} 使用 Lombok {@code @Builder}/{@code @Getter}，
 * 本接口的方法签名必须与 Lombok 生成的 getter <b>逐字对齐</b>
 * （{@code getName()} 而非 {@code name()}），否则编译期不报错、
 * 运行期抛 {@code AbstractMethodError}。
 */
public interface RoleProfile {

    /** 英文标识，如 {@code "main"}、{@code "coder"} */
    String getName();

    /** 展示名，可为空，为空时回退 {@link #getName()} */
    String getDisplayName();

    /** 人格三要素之一：角色定位 */
    String getRole();

    /** 人格三要素之一：目标 */
    String getGoal();

    /** 人格三要素之一：背景设定 */
    String getBackstory();
}