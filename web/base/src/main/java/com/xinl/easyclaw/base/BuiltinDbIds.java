package com.xinl.easyclaw.base;

/**
 * 内置「数据库」形态的统一标识锚点（V30.1）。
 * <p>
 * 同一个字符串 {@code "db"} 同时充当：SPI agentId（agent-db 模块）、modeId（mode-db 模块）、
 * 工作区 type（WorkspaceManager 白名单 / 初始化器）、内置场景名（SystemDataSeeder）。
 * 改名会多处失配——各消费方必须引用本常量，禁止裸写字符串，让编译器保证不漂移。
 * base 是 web/api、mode-db、agent-db 的公共依赖，常量放这里三方可达且不引入新耦合。
 */
public final class BuiltinDbIds {

    /** 数据库形态统一标识：agentId = modeId = workspace type = 场景名。 */
    public static final String DB = "db";

    private BuiltinDbIds() {
    }
}
