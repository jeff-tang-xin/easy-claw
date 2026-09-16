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
    /** 已登录但无权限（越权/跨组织）。 */
    FORBIDDEN,
    /** 资源不存在。 */
    NOT_FOUND,
    /** 冲突（如 slug/用户名已存在、乐观锁冲突）。 */
    CONFLICT,
    /** 入参校验失败。 */
    VALIDATION,
    /** 触发限流。 */
    RATE_LIMITED,
    /** 服务端内部错误。 */
    INTERNAL
}
