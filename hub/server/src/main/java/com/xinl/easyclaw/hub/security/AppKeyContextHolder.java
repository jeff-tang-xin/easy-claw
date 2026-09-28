package com.xinl.easyclaw.hub.security;

import com.xinl.easyclaw.hub.common.ApiException;

/**
 * {@link AppKeyContext} 的 ThreadLocal 持有器：{@link AppKeyAuthFilter} 设置、请求结束清理；
 * service/controller 读取。与 {@link CurrentUserHolder}（控制面 JWT）并列，专责 spoke 数据面。
 */
public final class AppKeyContextHolder {

    private static final ThreadLocal<AppKeyContext> CTX = new ThreadLocal<>();

    private AppKeyContextHolder() {
    }

    public static void set(AppKeyContext ctx) {
        CTX.set(ctx);
    }

    public static AppKeyContext get() {
        return CTX.get();
    }

    /** 取当前 appkey 上下文；未认证（理论上过滤器已拦）时抛 AUTH_REQUIRED。 */
    public static AppKeyContext require() {
        AppKeyContext ctx = CTX.get();
        if (ctx == null) {
            throw ApiException.authRequired();
        }
        return ctx;
    }

    public static void clear() {
        CTX.remove();
    }
}
