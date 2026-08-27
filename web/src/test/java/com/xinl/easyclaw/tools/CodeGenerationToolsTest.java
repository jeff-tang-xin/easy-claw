package com.xinl.easyclaw.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.xinl.easyclaw.python.GraalPyEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link CodeGenerationTools} 的缺陷修复验证。
 *
 * <p>每个用例都对应一个改造前会失败的真实缺陷，而非仅覆盖正常路径：
 *
 * <ul>
 *   <li>analyze_code：旧正则把 {@code if (} / {@code for (} / {@code new Foo(} 计为方法；
 *   <li>diff_code：旧实现逐行下标对齐，开头插一行即导致全文错位误报。
 * </ul>
 */
class CodeGenerationToolsTest {

    private static GraalPyEngine engine;
    private static CodeGenerationTools tools;
    private static boolean pythonReady;

    @BeforeAll
    static void setUp() {
        engine = new GraalPyEngine();
        engine.init();
        pythonReady = engine.isAvailable();
        tools = new CodeGenerationTools(new PythonCodeAnalyzer(engine));
    }

    @Nested
    @DisplayName("analyze_code")
    class AnalyzeCode {

        @Test
        @DisplayName("Python：控制流语句不得被计为函数（旧正则缺陷复现）")
        void controlFlowIsNotCountedAsFunction() {
            assumeTrue(pythonReady, "需要 GraalVM 运行时");
            // 只有 1 个函数，但内含 if/for/while/print(/dict( 等大量括号调用。
            // 旧实现会把这些全部计入方法数。
            String code =
                    """
                    def only_one_function(items):
                        result = {}
                        for item in items:
                            if item > 0:
                                while item > 1:
                                    item = item - 1
                                result[item] = str(item)
                        print(len(result))
                        return dict(result)
                    """;
            String out = tools.analyzeCode(code, "Python");
            assertAll(
                    () -> assertTrue(out.contains("AST 精确解析"), "应走 AST 路径: " + out),
                    () -> assertTrue(out.contains("函数数: 1"), "函数数应为 1，实际: " + out),
                    () -> assertTrue(out.contains("only_one_function"), "应列出函数名: " + out));
        }

        @Test
        @DisplayName("Python：async 函数与类应被正确识别")
        void countsAsyncFunctionsAndClasses() {
            assumeTrue(pythonReady, "需要 GraalVM 运行时");
            String code =
                    """
                    import os
                    from typing import List

                    class Service:
                        def sync_method(self):
                            pass

                        async def async_method(self, a, b):
                            pass

                    async def top_level():
                        pass
                    """;
            String out = tools.analyzeCode(code, "Python");
            assertAll(
                    () -> assertTrue(out.contains("函数数: 3"), "应识别 3 个函数（含 async）: " + out),
                    () -> assertTrue(out.contains("类数: 1"), "应识别 1 个类: " + out),
                    () -> assertTrue(out.contains("[async]"), "应标注 async: " + out),
                    () -> assertTrue(out.contains("os"), "应列出导入: " + out));
        }

        @Test
        @DisplayName("Python：语法错误应明确报错而非返回错误数字")
        void syntaxErrorIsReported() {
            assumeTrue(pythonReady, "需要 GraalVM 运行时");
            String out = tools.analyzeCode("def broken(:\n  pass", "Python");
            assertTrue(out.startsWith("❌"), "应明确报错: " + out);
        }

        @Test
        @DisplayName("Java：控制流语句不得被计为方法（正则修复验证）")
        void javaControlFlowIsNotCountedAsMethod() {
            // Java 走正则路径。此处只有 2 个方法，但有多处 if/for/while/new。
            String code =
                    """
                    public class Demo {
                        public void first(int n) {
                            if (n > 0) {
                                for (int i = 0; i < n; i++) {
                                    while (i > 2) {
                                        i--;
                                    }
                                }
                            }
                            Object o = new Object();
                        }

                        private String second() {
                            return String.valueOf(1);
                        }
                    }
                    """;
            String out = tools.analyzeCode(code, "Java");
            assertAll(
                    () -> assertTrue(out.contains("方法数: 2"), "方法数应为 2，实际: " + out),
                    () -> assertTrue(out.contains("类/接口/枚举/记录数: 1"), "类数应为 1: " + out));
        }

        @Test
        @DisplayName("空输入应友好返回")
        void blankCodeIsRejected() {
            assertTrue(tools.analyzeCode("   ", "Java").startsWith("❌"));
            assertTrue(tools.analyzeCode(null, "Java").startsWith("❌"));
        }
    }

    @Nested
    @DisplayName("diff_code")
    class DiffCode {

        @Test
        @DisplayName("开头插入一行不应导致后续全部误报（旧实现缺陷复现）")
        void insertingLeadingLineDoesNotShiftEverything() {
            assumeTrue(pythonReady, "需要 GraalVM 运行时");
            String before = "line A\nline B\nline C\nline D\nline E";
            String after = "NEW HEADER\nline A\nline B\nline C\nline D\nline E";

            String out = tools.diffCode(before, after);
            assertAll(
                    () -> assertTrue(out.contains("新增行: 1"), "应只新增 1 行，实际: " + out),
                    () -> assertTrue(out.contains("删除行: 0"), "不应有删除，实际: " + out),
                    () -> assertFalse(out.contains("逐行近似"), "应走 difflib 路径: " + out));
        }

        @Test
        @DisplayName("完全相同应返回 ✅")
        void identicalCode() {
            String same = "a\nb\nc";
            assertTrue(tools.diffCode(same, same).contains("✅"));
        }

        @Test
        @DisplayName("应统计真实的增删行数与相似度")
        void reportsAddedRemovedAndSimilarity() {
            assumeTrue(pythonReady, "需要 GraalVM 运行时");
            String before = "keep\nremove me\nkeep2";
            String after = "keep\nkeep2\nadd me\nadd me 2";

            String out = tools.diffCode(before, after);
            assertAll(
                    () -> assertTrue(out.contains("新增行: 2"), out),
                    () -> assertTrue(out.contains("删除行: 1"), out),
                    () -> assertTrue(out.contains("相似度"), out));
        }

        @Test
        @DisplayName("null 参数应按空串处理，不抛异常")
        void nullArgsAreTolerated() {
            String out = tools.diffCode(null, "x");
            assertFalse(out.startsWith("❌"), "不应报错: " + out);
        }
    }
}
