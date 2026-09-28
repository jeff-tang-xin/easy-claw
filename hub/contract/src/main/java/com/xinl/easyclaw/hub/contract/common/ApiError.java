package com.xinl.easyclaw.hub.contract.common;

/**
 * 统一错误响应体。
 *
 * @param code    稳定错误码（如 USER_NOT_FOUND / FORBIDDEN / CONFLICT）
 * @param message 人类可读信息
 * @param details 可选的补充细节（字段错误等）
 */
public record ApiError(String code, String message, Object details) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }
}
