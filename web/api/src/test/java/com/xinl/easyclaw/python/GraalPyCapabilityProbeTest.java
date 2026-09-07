package com.xinl.easyclaw.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GraalPy 能力探针：用于评估「基于 Python 实现常用工具」是否可行。
 *
 * <p>与 {@link GraalPySmokeTest} 的区别：冒烟测试只验证 sys/json 这类内置模块，
 * 本探针验证 ast/difflib/tokenize 等<b>纯 Python 实现</b>的标准库模块是否可加载，
 * 以及 Context 创建/复用的真实耗时。这两点决定方案可行性。
 */
class GraalPyCapabilityProbeTest {

    private static Context newContext() {
        return Context.newBuilder("python").allowAllAccess(true).build();
    }

    @Test
    @DisplayName("探针：difflib 可用且能产出真正的 LCS 差异")
    void difflibIsAvailable() {
        try (Context ctx = newContext()) {
            // 场景：code2 在开头插入一行。逐行下标对齐会误报全文差异，
            // difflib 应只报告 1 处插入。
            Value result =
                    ctx.eval(
                            "python",
                            """
                            import difflib
                            a = ['line1', 'line2', 'line3']
                            b = ['inserted', 'line1', 'line2', 'line3']
                            d = list(difflib.unified_diff(a, b, lineterm=''))
                            # 统计真实的增删行数（排除 +++/--- 文件头）
                            adds = [x for x in d if x.startswith('+') and not x.startswith('+++')]
                            dels = [x for x in d if x.startswith('-') and not x.startswith('---')]
                            '%d,%d' % (len(adds), len(dels))
                            """);
            assertEquals("1,0", result.asString(), "difflib 应识别为 1 处插入、0 处删除");
        }
    }

    @Test
    @DisplayName("探针：ast 可用且能精确统计函数/类数量")
    void astIsAvailable() {
        try (Context ctx = newContext()) {
            // 含 if/for/while/调用括号的代码：正则法会把它们误计为方法
            Value result =
                    ctx.eval(
                            "python",
                            """
                            import ast
                            src = '''
                            class Foo:
                                def bar(self):
                                    if (1 > 0):
                                        for i in range(3):
                                            print(i)
                                    while (False):
                                        pass
                                    return len([1])
                            '''
                            tree = ast.parse(src.strip())
                            funcs = [n for n in ast.walk(tree) if isinstance(n, ast.FunctionDef)]
                            classes = [n for n in ast.walk(tree) if isinstance(n, ast.ClassDef)]
                            '%d,%d' % (len(funcs), len(classes))
                            """);
            assertEquals("1,1", result.asString(), "ast 应精确报告 1 函数 1 类，不受 if/for/while 括号干扰");
        }
    }

    @Test
    @DisplayName("探针：tokenize / re / textwrap / hashlib 等常用模块可加载")
    void commonModulesAreAvailable() {
        try (Context ctx = newContext()) {
            Value result =
                    ctx.eval(
                            "python",
                            """
                            mods = []
                            for name in ['tokenize', 're', 'textwrap', 'hashlib', 'csv',
                                         'base64', 'itertools', 'collections', 'statistics',
                                         'datetime', 'urllib.parse', 'html.parser', 'sqlite3']:
                                try:
                                    __import__(name)
                                    mods.append(name)
                                except Exception as e:
                                    mods.append('FAIL:' + name)
                            ','.join(mods)
                            """);
            String mods = result.asString();
            assertTrue(!mods.contains("FAIL:"), "存在无法加载的模块: " + mods);
        }
    }

    @Test
    @DisplayName("探针：测量 Context 首次创建 / 复用 / 重建的耗时")
    void measureContextCost() {
        long t0 = System.currentTimeMillis();
        try (Context ctx = newContext()) {
            ctx.eval("python", "1");
        }
        long firstCreate = System.currentTimeMillis() - t0;

        // 复用同一 Context 连续 eval
        long t1 = System.currentTimeMillis();
        try (Context ctx = newContext()) {
            ctx.eval("python", "import difflib, ast");
            long afterImport = System.currentTimeMillis() - t1;

            long t2 = System.currentTimeMillis();
            for (int i = 0; i < 20; i++) {
                ctx.eval("python", "sum(range(100))");
            }
            long reuse20 = System.currentTimeMillis() - t2;

            // 重建 Context 的开销
            long t3 = System.currentTimeMillis();
            for (int i = 0; i < 3; i++) {
                try (Context c = newContext()) {
                    c.eval("python", "1");
                }
            }
            long recreate3 = System.currentTimeMillis() - t3;

            System.out.println("=== GRAALPY-COST-PROBE ===");
            System.out.println("firstCreate_ms=" + firstCreate);
            System.out.println("importDifflibAst_ms=" + afterImport);
            System.out.println("reuse20evals_ms=" + reuse20);
            System.out.println("recreate3contexts_ms=" + recreate3);
            System.out.println("=== END-PROBE ===");
        }
    }
}
