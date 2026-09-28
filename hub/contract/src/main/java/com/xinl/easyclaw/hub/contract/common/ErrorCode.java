package com.xinl.easyclaw.hub.contract.common;

/**
 * 稳定错误码（客户端据此分支，勿当用户文案）。与 {@link ApiError#code()} 对应。
 */
public enum ErrorCode {
    /** 凭证无效 / token 过期或非法。 */
    AUTH_INVALID,
    /** 需要登录。 */
    AUTH_REQUIRED,
    /** 首次登录须先修改密码（其余 API 暂不可用）。 */
    PASSWORD_CHANGE_REQUIRED,
    /** 密码已超过有效期，禁止登录，须联系平台管理员重置。 */
    PASSWORD_EXPIRED,
    /** 已登录但无权限（越权/跨组织）。 */
    FORBIDDEN,
    /** 资源不存在。 */
    NOT_FOUND,
    /** 冲突（如 slug/用户名已存在）。 */
    CONFLICT,
    /** 乐观锁版本冲突（编辑基于的 expectedVersion 已落后），details 携带最新快照供手动合并。 */
    VERSION_CONFLICT,
    /** 同项目下 topic 已存在（未删除条目项目内唯一）。 */
    TOPIC_EXISTS,
    /** 入参校验失败。 */
    VALIDATION,
    /** 触发限流。 */
    RATE_LIMITED,
    /** 服务端内部错误。 */
    INTERNAL
}
