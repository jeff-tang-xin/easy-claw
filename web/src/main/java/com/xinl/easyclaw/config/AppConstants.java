package com.xinl.easyclaw.config;

/**
 * 应用级常量。
 *
 * <p>{@link #DEFAULT_USER_ID} 为单用户模式下固定的系统用户 ID。
 * AgentScope harness 会以 userId 作为 Workspace 内运行时数据目录名
 * （{@code <workspace>/<userId>/agents/...}），因此该值同时决定了
 * 运行时数据落在 {@code <workspace>/.easyClaw/} 下，符合目录规范
 * （系统级 {@code ~/.easyClaw}，Workspace 级 {@code <workspace>/.easyClaw/agent}）。</p>
 */
public final class AppConstants {

    /** 单用户模式系统用户 ID（同时是 Workspace 内 harness 运行时数据目录名）。 */
    public static final String DEFAULT_USER_ID = "local";

    /** 历史版本使用的旧用户 ID，仅用于启动迁移与清理。 */
    public static final String LEGACY_USER_ID = "default-user";

    private AppConstants() {
    }
}
