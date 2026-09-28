package com.xinl.easyclaw.blackboard;

/**
 * 共享记录本（blackboard）相关的 RuntimeContext 键名常量。
 * <p>
 * {@link #CTX_KEY} 由主会话在构建 RuntimeContext 时写入（见 {@code AgentService#buildContext}），
 * 值为 {@link #DEFAULT_BOOK}（固定本名）；子 Agent 创建时走 {@code RuntimeContext.builder(parentRc)}，
 * 字符串属性会被复制继承 —— 因此子 Agent 与父会话落到同一个记录本上。
 * <p>
 * 记录本按「工作区（local）/ 项目+用户（cloud）」隔离，<b>不按会话分本</b>：
 * 它是个人跨会话的心得账本，条目上的 sessionId 字段负责标注「哪次会话写的」。
 */
public final class BlackboardKeys {

    /** 记录本名在 RuntimeContext 字符串属性中的键名 */
    public static final String CTX_KEY = "ec.blackboardKey";

    /** 固定本名：同一工作区（local）/ 同一项目+用户（cloud）只有一块活跃黑板 */
    public static final String DEFAULT_BOOK = "main";

    private BlackboardKeys() {
    }
}
