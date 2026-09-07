package com.xinl.easyclaw.blackboard;

/**
 * 共享记录本（blackboard）相关的 RuntimeContext 键名常量。
 * <p>
 * {@link #CTX_KEY} 由主会话在构建 RuntimeContext 时写入（见 {@code AgentService#buildContext}），
 * 子 Agent 创建时走 {@code RuntimeContext.builder(parentRc)}，字符串属性会被复制继承 ——
 * 因此子 Agent 的 sessionId 虽是 {@code sub-<UUID>}，读到的 blackboardKey 与父会话相同，
 * 从而落到同一个记录本文件上。
 */
public final class BlackboardKeys {

    /** 记录本隔离键（父会话 id）在 RuntimeContext 字符串属性中的键名 */
    public static final String CTX_KEY = "ec.blackboardKey";

    private BlackboardKeys() {
    }
}
