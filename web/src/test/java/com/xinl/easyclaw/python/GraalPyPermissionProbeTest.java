package com.xinl.easyclaw.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 权限探针：验证在<b>不使用</b> allowAllAccess(true) 的前提下，
 * 纯 Python 标准库模块（ast / difflib）是否仍可加载。
 *
 * <p>背景：GraalPySmokeTest 为了 {@code import sys} 开了全权限，
 * 等价于允许 Python 读写文件、启动进程。工具层脚本只做纯计算，
 * 不应保留这份权限。本探针用于确认收紧后功能不受影响。
 */
class GraalPyPermissionProbeTest {

    @Test
    @DisplayName("探针：最小权限下 ast + difflib 是否可用")
    void restrictedContextCanImportPureStdlib() {
        try (Context ctx =
                Context.newBuilder("python")
                        .allowExperimentalOptions(true)
                        .option("python.PosixModuleBackend", "java")
                        .build()) {
            String out =
                    ctx.eval(
                                    "python",
                                    """
                                    import ast, difflib, json
                                    t = ast.parse('def f():\\n    pass')
                                    n = len([x for x in ast.walk(t) if isinstance(x, ast.FunctionDef)])
                                    d = len(list(difflib.unified_diff(['a'], ['b'], lineterm='')))
                                    json.dumps({'funcs': n, 'diffLines': d})
                                    """)
                            .asString();
            System.out.println("=== RESTRICTED-OK === " + out);
            assertTrue(out.contains("\"funcs\": 1"), "ast 应可用: " + out);
        } catch (Exception e) {
            // 明确打印失败原因，便于判断需要放开哪一项权限
            System.out.println("=== RESTRICTED-FAILED === " + e.getClass().getName() + ": " + e.getMessage());
            fail("最小权限下无法加载纯 Python stdlib: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("探针：最小权限下文件读写应被拒绝（安全基线）")
    void restrictedContextCannotDoFileIo() {
        try (Context ctx =
                Context.newBuilder("python")
                        .allowExperimentalOptions(true)
                        .option("python.PosixModuleBackend", "java")
                        .build()) {
            String out =
                    ctx.eval(
                                    "python",
                                    """
                                    try:
                                        f = open('probe_should_not_exist.txt', 'w')
                                        f.write('x')
                                        f.close()
                                        r = 'IO_ALLOWED'
                                    except Exception as e:
                                        r = 'IO_DENIED'
                                    r
                                    """)
                            .asString();
            System.out.println("=== IO-CHECK === " + out);
            assertEquals("IO_DENIED", out, "最小权限下不应允许写文件");
        }
    }
}
