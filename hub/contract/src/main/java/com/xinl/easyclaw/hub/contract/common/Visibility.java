package com.xinl.easyclaw.hub.contract.common;

/**
 * 资源可见性。
 */
public enum Visibility {
    /** 仅 owner/被授权者。 */
    PRIVATE,
    /** 组织内成员。 */
    TEAM,
    /** 公开（跨组织可读）。 */
    PUBLIC
}
