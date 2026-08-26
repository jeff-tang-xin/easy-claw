package com.xinl.easyclaw.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 入口层校验失败时抛出的异常族。
 * <p>
 * 独立成类而非复用 {@code IllegalArgumentException}，是为了让 HTTP 状态码语义准确：
 * 参数非法 → 400、越权 → 403、对象不存在 → 404。三者若混为一谈，
 * 攻击者可以用「400 还是 403」推断工作区是否存在（信息泄漏），
 * 前端也无法区分「该重填参数」和「该换账号」。
 */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    /** 参数格式非法（长度、字符集、必填缺失） */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    /** 资源存在但调用方无权访问 */
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    /** 资源不存在 */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
