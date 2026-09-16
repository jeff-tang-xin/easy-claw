package com.xinl.easyclaw.hub.security;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.hub.contract.common.ApiError;
import com.xinl.easyclaw.hub.contract.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 轻量 JWT 过滤器（不依赖完整 Spring Security）：保护 {@code /api/**} 中除公开端点外的所有路径。
 * 合法 access token → 解析出 {@link AuthContext} 放入 {@link CurrentUserHolder}；非法/缺失 → 401 {@link ApiError}。
 * 角色鉴权不在此处（无状态），由 service 层按当前用户 id 查 memberships 判定。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 公开端点（无需 access token）；logout 需登录，故不在此列。注册已取消（平台管理员引导 + 管理员添加用户）。 */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login", "/api/auth/refresh");

    /** mcp（首登强制改密）用户放行清单：改密/登出/查自身，其余 API 一律 403。 */
    private static final Set<String> MCP_ALLOWED_PATHS = Set.of(
            "/api/auth/change-password", "/api/auth/logout", "/api/me");

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 非 /api/**（控制台页面/静态资源/健康检查等）不拦；
        // /api/gateway/v1/** 与 /api/spoke/** 走 AppKeyAuthFilter（spoke 数据面 appkey 认证），此处放行；
        // /api/ 下其余仅公开端点不拦。
        if (!path.startsWith("/api/")) {
            return true;
        }
        if (path.startsWith("/api/gateway/v1/") || path.startsWith("/api/spoke/")) {
            return true;
        }
        return PUBLIC_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.AUTH_REQUIRED, "需要登录");
            return;
        }
        try {
            DecodedJWT jwt = jwtService.verify(header.substring(7));
            Long userId = Long.valueOf(jwt.getSubject());
            String username = jwt.getClaim("username").asString();
            Long currentOrgId = jwt.getClaim("act_org").isNull() ? null : jwt.getClaim("act_org").asLong();
            // 首登强制改密门禁：mcp=true 时仅放行改密/登出/查自身；老 token 无 mcp claim 视为 false
            Boolean mcp = jwt.getClaim("mcp").asBoolean();
            if (Boolean.TRUE.equals(mcp) && !MCP_ALLOWED_PATHS.contains(request.getRequestURI())) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                        ErrorCode.PASSWORD_CHANGE_REQUIRED, "首次登录须先修改密码");
                return;
            }
            CurrentUserHolder.set(new AuthContext(userId, username, currentOrgId));
            chain.doFilter(request, response);
        } catch (JWTVerificationException | NumberFormatException e) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.AUTH_INVALID, "凭证无效或已过期");
        } finally {
            CurrentUserHolder.clear();
        }
    }

    private void writeError(HttpServletResponse response, int status, ErrorCode code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiError.of(code.name(), message)));
    }
}
