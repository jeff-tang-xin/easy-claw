package com.xinl.easyclaw.hub.common;

import com.xinl.easyclaw.hub.contract.common.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带稳定错误码 + HTTP 状态 + 人类可读信息，由 server 层 GlobalExceptionHandler 统一转 {@code ApiError}。
 */
@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final HttpStatus status;
    private final transient Object details;

    private ApiException(ErrorCode code, HttpStatus status, String message, Object details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }

    public static ApiException of(ErrorCode code, HttpStatus status, String message) {
        return new ApiException(code, status, message, null);
    }

    public static ApiException authRequired() {
        return of(ErrorCode.AUTH_REQUIRED, HttpStatus.UNAUTHORIZED, "需要登录");
    }

    public static ApiException authInvalid(String message) {
        return of(ErrorCode.AUTH_INVALID, HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return of(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, message);
    }

    public static ApiException notFound(String message) {
        return of(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return of(ErrorCode.CONFLICT, HttpStatus.CONFLICT, message);
    }

    /** 409 并携带补充数据（如乐观锁冲突时回传最新快照，供调用方手动合并）。 */
    public static ApiException conflict(String message, Object details) {
        return new ApiException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, message, details);
    }

    public static ApiException validation(String message) {
        return of(ErrorCode.VALIDATION, HttpStatus.BAD_REQUEST, message);
    }
}
