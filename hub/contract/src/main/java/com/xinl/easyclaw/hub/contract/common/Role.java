package com.xinl.easyclaw.hub.contract.common;

/**
 * 组织内角色（服务端判定的唯一权威；菜单显隐仅为体验）。
 */
public enum Role {
    /** 组织一切 + 删组织 + 改 owner。 */
    OWNER,
    /** 成员管理、项目管理、内容/key 管理。 */
    ADMIN,
    /** 使用组织/项目下的资源。 */
    MEMBER,
    /** 只读。 */
    GUEST
}
