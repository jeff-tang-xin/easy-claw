package com.xinl.easyclaw.hub.gateway;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xinl.easyclaw.hub.security.AppKeyContext;
import com.xinl.easyclaw.hub.security.AppKeyContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 网关端点（OpenAI 兼容，设计 §4.1/§7）：
 * - {@code POST /api/gateway/v1/chat/completions}：appkey 认证 → model 路由 → 转发真实 provider；
 *   {@code stream:true} 时 SSE 逐事件透传（anthropic 协议在流上转换为 OpenAI chunk）。
 * - {@code GET /api/gateway/v1/models}：该 appkey 绑定可见的模型清单。
 * 错误一律 OpenAI 兼容错误格式（{@link GatewayErrors}），与控制面 ApiError 区分。
 */
@RestController
public class GatewayController {

    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    /**
     * 流式分支直接返回 {@code StreamingResponseBody}（由专门的 ReturnValueHandler 异步处理）。
     * 不能包在 {@code ResponseEntity<?>} 里：HttpEntityMethodProcessor 对通配类型不做
     * StreamingResponseBody 特判，会走 HttpMessageConverter 并报 "No converter"。
     */
    @PostMapping("/api/gateway/v1/chat/completions")
    public Object chatCompletions(@RequestBody byte[] body, HttpServletRequest request,
                                  HttpServletResponse response) {
        AppKeyContext ctx = AppKeyContextHolder.require();
        GatewayService.GatewayOutcome outcome = gatewayService.chatCompletion(ctx, body, clientIp(request));
        return switch (outcome) {
            case GatewayService.GatewayOutcome.Json j -> ResponseEntity.status(j.status())
                    .contentType(MediaType.APPLICATION_JSON).body(j.body());
            case GatewayService.GatewayOutcome.Stream s -> {
                response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
                response.setHeader("Cache-Control", "no-cache");
                response.setHeader("X-Accel-Buffering", "no");
                yield s.body();
            }
        };
    }

    @GetMapping("/api/gateway/v1/models")
    public ObjectNode models() {
        return gatewayService.listModels(AppKeyContextHolder.require());
    }

    /** 网关自身异常 → OpenAI 兼容错误体（局部处理，不进控制面 GlobalExceptionHandler）。 */
    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ObjectNode> handleGatewayException(GatewayException e) {
        return ResponseEntity.status(e.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(GatewayErrors.openAiError(e.getMessage(), e.type(), e.code()));
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
