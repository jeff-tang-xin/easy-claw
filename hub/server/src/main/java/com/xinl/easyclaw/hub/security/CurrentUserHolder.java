package com.xinl.easyclaw.hub.security;

import com.xinl.easyclaw.hub.common.ApiException;

/**
 * 当前认证用户的 ThreadLocal 持有器：JWT 过滤器设置、请求结束清理；service/controller 读取。
 */
public final class CurrentUserHolder {

    private static final ThreadLocal<AuthContext> CTX = new ThreadLocal<>();

    private CurrentUserHolder() {
    }

    public static void set(AuthContext ctx) {
        CTX.set(ctx);
    }

    public static AuthContext get() {
        return CTX.get();
    }

    /** 取当前用户 id；未认证（理论上过滤器已拦）时抛 AUTH_REQUIRED。 */
    public static Long requireUserId() {
        AuthContext ctx = CTX.get();
        if (ctx == null || ctx.userId() == null) {
            throw ApiException.authRequired();
        }
        return ctx.userId();
    }

    public static Long currentOrgId() {
        AuthContext ctx = CTX.get();
        return ctx == null ? null : ctx.currentOrgId();
    }

    public static void clear() {
        CTX.remove();
    }
}
