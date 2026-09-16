package com.xinl.easyclaw.hub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xinl.easyclaw.hub.common.KeyHashes;
import com.xinl.easyclaw.hub.contract.common.ApiError;
import com.xinl.easyclaw.hub.contract.common.ErrorCode;
import com.xinl.easyclaw.hub.entity.AppKeyEntity;
import com.xinl.easyclaw.hub.repository.AppKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * spoke 数据面 appkey 认证过滤器：拦 {@code /api/gateway/v1/**}（LLM 网关）与
 * {@code /api/spoke/**}（spoke 服务）——来自子系统的所有请求一律校验 appkey。
 * 凭证为 appkey 明文（{@code Authorization: Bearer eck-...}）：SHA-256 命中且 active 才放行，
 * 解析结果放入 {@link AppKeyContextHolder}，下游一切配置与权限判断均基于此上下文。
 * 错误格式按面分流：网关前缀回 OpenAI 兼容错误（LLM 客户端按此解析），spoke 前缀回控制面 ApiError。
 * （OpenAI 错误体此处内联构造而不复用 gateway.GatewayErrors：避免 security↔gateway 包环依赖。）
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AppKeyAuthFilter extends OncePerRequestFilter {

    static final String GATEWAY_PATH_PREFIX = "/api/gateway/v1/";
    static final String SPOKE_PATH_PREFIX = "/api/spoke/";

    private final AppKeyRepository appKeys;
    private final ObjectMapper objectMapper;

    public AppKeyAuthFilter(AppKeyRepository appKeys, ObjectMapper objectMapper) {
        this.appKeys = appKeys;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(GATEWAY_PATH_PREFIX) && !path.startsWith(SPOKE_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        boolean gatewayFace = request.getRequestURI().startsWith(GATEWAY_PATH_PREFIX);
        String header = request.getHeader("Authorization");
        String presented = (header != null && header.startsWith("Bearer ")) ? header.substring(7).trim() : "";
        AppKeyEntity key = presented.isEmpty() ? null
                : appKeys.findByKeyHash(KeyHashes.sha256Hex(presented)).orElse(null);
        if (key == null) {
            writeError(response, gatewayFace, "无效的 appkey");
            return;
        }
        if (!"active".equals(key.getStatus())) {
            writeError(response, gatewayFace, "appkey 已吊销");
            return;
        }
        AppKeyContextHolder.set(new AppKeyContext(key.getId(), key.getName(), key.getKeyPrefix(),
                key.getOrgId(), key.getCreatedBy()));
        try {
            chain.doFilter(request, response);
        } finally {
            AppKeyContextHolder.clear();
        }
    }

    private void writeError(HttpServletResponse response, boolean gatewayFace, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        if (gatewayFace) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("message", message);
            error.put("type", "invalid_request_error");
            error.put("code", "invalid_api_key");
            ObjectNode root = objectMapper.createObjectNode();
            root.set("error", error);
            response.getWriter().write(objectMapper.writeValueAsString(root));
        } else {
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiError.of(ErrorCode.AUTH_INVALID.name(), message)));
        }
    }
}
