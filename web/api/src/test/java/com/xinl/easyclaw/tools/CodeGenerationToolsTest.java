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
        tools = new CodeGenerationTools(new PythonCodeAnalyzer(engine),
                new com.xinl.easyclaw.python.PythonSandbox());
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

    @Nested
    @DisplayName("inspect_data")
    class InspectData {

        @Test
        @DisplayName("JSON：语法错误应给出精确行号")
        void jsonSyntaxErrorReportsLine() {
            assumeTrue(pythonReady, "需要 GraalVM 运行时");
            String bad = "{\n  \"a\": 1,\n  \"b\": 2,,\n  \"c\": 3\n}";
            String out = tools.inspectData(bad, "json", null);
            assertAll(
                    () -> assertTrue(out.contains("JSON 无效"), out),
                    () -> assertTrue(out.contains("第 3 行"), "应定位到第 3 行: " + out));
        }

        @Test
        @DisplayName("JSON：重复键被静默覆盖时必须告警（朴素解析发现不了）")
        void jsonDuplicateKeysAreDetected() {
            assumeTrue(pythonReady, "需要 GraalVM 运行时");
            // json.loads 不会报错，后一个 timeout 静默覆盖前一个
            String text = "{\"timeout\": 30, \"retries\": 3, \"timeout\": 60}";
            String out = tools.inspectData(text, "json", null);
            assertAll(
                    () -> assertTrue(out.contains("JSON 有效"), out),
                    () -> assertTrue(out.contains("重复键"), "应告警重复键: " + out),
                    () -> assertTrue(out.contains("timeout"), "应指出是 timeout: " + out));
        }

        @Test
        @DisplayName("JSON：数组内对象字段不一致应给出下标")
        void jsonInconsistentObjectsAreDetected() {
            assumeTrue(pythonReady, "需要 GraalVM 运行时");
            String text = "[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"},{\"id\":3}]";
            String out = tools.inspectData(text, "json", null);
            assertAll(
                    () -> assertTrue(out.contains("字段与首个对象不一致"), out),
                    () -> assertTrue(out.contains("2"), "应指出下标 2: " + out));
        }

        @Test
        @DisplayName("CSV：引号内的逗号不应被误判为错列（按逗号切分的经典缺陷）")
        void csvQuotedCommaIsNotMiscounted() {
            assumeTrue(pythonReady, "需要 GraalVM 运行时");
            // 第 2 行两个字段内含逗号，朴素 split(",") 会认为该行有 5 个字段
            String csv = "id,name,address\n1,\"Smith, John\",\"Beijing, CN\"\n2,Li,Shanghai";
            String out = tools.inspectData(csv, "csv", ",");
            assertAll(
                    () -> assertTrue(out.contains("结构一致"), "不应报错列: " + out),
                    () -> assertTrue(out.contains("列数: 3"), "应为 3 列: " + out),
                    () -> assertTrue(out.contains("数据行数: 2"), out));
        }

        @Test
        @DisplayName("CSV：字段数真不一致时应给出行号")
        void csvMalformedRowReportsLine() {
            assumeTrue(pythonReady, "需要 GraalVM 运行时");
            String csv = "a,b,c\n1,2,3\n4,5\n6,7,8";
            String out = tools.inspectData(csv, "csv", ",");
            assertAll(
                    () -> assertTrue(out.contains("字段数不一致"), out),
                    () -> assertTrue(out.contains("第 3 行"), "应定位第 3 行: " + out));
        }

        @Test
        @DisplayName("CSV：重复表头与空值分布应被报告")
        void csvDuplicateHeaderAndEmptyCells() {
            assumeTrue(pythonReady, "需要 GraalVM 运行时");
            String csv = "id,name,name\n1,,x\n2,,y";
            String out = tools.inspectData(csv, "csv", ",");
            assertAll(
                    () -> assertTrue(out.contains("表头重复"), out),
                    () -> assertTrue(out.contains("空值分布"), out));
        }

        @Test
        @DisplayName("参数缺失或格式不支持应友好提示")
        void invalidArguments() {
            assertTrue(tools.inspectData("  ", "json", null).startsWith("❌"));
            assertTrue(tools.inspectData("{}", null, null).startsWith("❌"));
            assumeTrue(pythonReady, "需要 GraalVM 运行时");
            assertTrue(tools.inspectData("{}", "yaml", null).contains("不支持"));
        }
    }
}
