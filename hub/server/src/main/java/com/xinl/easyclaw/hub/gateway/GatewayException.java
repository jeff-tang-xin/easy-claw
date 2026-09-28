package com.xinl.easyclaw.hub.gateway;

/**
 * 网关自身异常：以 OpenAI 兼容错误格式回给调用方（vendored/OpenAI SDK 客户端按此格式解析），
 * 区别于控制面的 {@code ApiError}。type 对应 OpenAI error.type（invalid_request_error 等），
 * code 为机器可读码（invalid_api_key / model_not_found / upstream_error ...）。
 */
public class GatewayException extends RuntimeException {

    private final int status;
    private final String type;
    private final String code;

    public GatewayException(int status, String message, String code) {
        this(status, message, "invalid_request_error", code);
    }

    public GatewayException(int status, String message, String type, String code) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String code() {
        return code;
    }
}
