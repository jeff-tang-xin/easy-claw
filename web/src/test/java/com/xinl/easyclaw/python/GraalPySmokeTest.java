package com.xinl.easyclaw.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GraalPy 内置解释器冒烟测试。
 *
 * <p>验证 polyglot + python 依赖确实可用（而非仅仅解析成功）。
 */
class GraalPySmokeTest {

    /** 允许访问所有资源的 Context，内置解释器场景下需要 allowAllAccess 才能用标准库。 */
    private static Context newContext() {
        return Context.newBuilder("python").allowAllAccess(true).build();
    }

    @Test
    @DisplayName("python 语言引擎可被创建并执行表达式")
    void canEvaluateSimpleExpression() {
        try (Context ctx = newContext()) {
            Value result = ctx.eval("python", "1 + 2");
            assertEquals(3, result.asInt());
        }
    }

    @Test
    @DisplayName("可以调用 Python 标准库")
    void canUseStandardLibrary() {
        try (Context ctx = newContext()) {
            Value result =
                    ctx.eval(
                            "python",
                            """
                            import sys, json
                            json.dumps({'v': sys.version_info.major})
                            """);
            assertTrue(result.asString().contains("\"v\": 3"), "应返回 Python 3");
        }
    }

    @Test
    @DisplayName("Java 与 Python 之间可以互传数据")
    void canExchangeDataWithJava() {
        try (Context ctx = newContext()) {
            ctx.getBindings("python").putMember("javaValue", 40);
            Value result = ctx.eval("python", "javaValue + 2");
            assertEquals(42, result.asInt());
        }
    }

    @Test
    @DisplayName("可以调用 Python 中定义的函数")
    void canInvokePythonFunction() {
        try (Context ctx = newContext()) {
            ctx.eval(
                    "python",
                    """
                    def greet(name):
                        return f'hello {name}'
                    """);
            Value greet = ctx.getBindings("python").getMember("greet");
            assertEquals("hello claw", greet.execute("claw").asString());
        }
    }
}
