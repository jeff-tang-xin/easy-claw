package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.contract.common.ApiError;
import com.xinl.easyclaw.hub.contract.common.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一异常 → {@link ApiError} 错误体。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ApiError(ex.getCode().name(), ex.getMessage(), ex.getDetails()));
    }

    /** @Valid 校验失败 → VALIDATION，details 为 {字段: 提示}。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.VALIDATION.name(), "入参校验失败", fields));
    }

    /** 路由/静态资源未命中（如已删除的端点）→ 404，避免被兜底成 500。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(ErrorCode.NOT_FOUND.name(), "资源不存在", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex) {
        log.error("[hub] 未处理异常: {}", ex.getMessage(), ex);
        return ResponseEntity.internalServerError()
                .body(new ApiError(ErrorCode.INTERNAL.name(), "服务端内部错误", null));
    }
}
