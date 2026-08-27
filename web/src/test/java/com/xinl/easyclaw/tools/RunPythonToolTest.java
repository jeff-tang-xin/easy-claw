package com.xinl.easyclaw.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.xinl.easyclaw.python.GraalPyEngine;
import com.xinl.easyclaw.python.PythonSandbox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * run_python 工具层用例。
 *
 * <p>关注点不是"Python 能不能跑"（那是 PythonSandboxTest 的事），而是
 * <b>返回给模型的文本是否让模型能正确决策</b>——尤其是失败与空输出这两种
 * 容易让模型陷入重试循环的情况。
 */
class RunPythonToolTest {

    private static CodeGenerationTools tools;
    private static boolean ready;

    @BeforeAll
    static void setUp() {
        PythonSandbox sandbox = new PythonSandbox();
        GraalPyEngine engine = new GraalPyEngine();
        engine.init();
        tools = new CodeGenerationTools(new PythonCodeAnalyzer(engine), sandbox);
        ready = sandbox.isAvailable();
    }

    @Test
    @DisplayName("正常计算：应返回 print 的输出")
    void runsComputation() {
        assumeTrue(ready, "需要 GraalVM");
        String out = tools.runPython("import math\nprint(math.factorial(20))");
        assertAll(
                () -> assertTrue(out.contains("✅"), out),
                () -> assertTrue(out.contains("2432902008176640000"), out));
    }

    @Test
    @DisplayName("空输入应直接拒绝，不进解释器")
    void rejectsBlank() {
        String out = tools.runPython("   ");
        assertTrue(out.contains("❌"), out);
    }

    @Test
    @DisplayName("忘记 print 时必须明确提示，否则模型会反复空跑")
    void hintsMissingPrint() {
        assumeTrue(ready, "需要 GraalVM");
        String out = tools.runPython("1 + 1");
        assertAll(
                () -> assertTrue(out.contains("没有任何输出"), out),
                () -> assertTrue(out.contains("print()"), out));
    }

    @Test
    @DisplayName("运行时错误应带回 traceback 供模型自行修正")
    void reportsRuntimeError() {
        assumeTrue(ready, "需要 GraalVM");
        String out = tools.runPython("print(1/0)");
        assertAll(
                () -> assertTrue(out.contains("❌"), out),
                () -> assertTrue(out.contains("ZeroDivisionError"), "应含异常类型: " + out));
    }

    @Test
    @DisplayName("失败前已产生的输出不能丢——那是定位问题的线索")
    void keepsPartialOutputOnFailure() {
        assumeTrue(ready, "需要 GraalVM");
        String out = tools.runPython("print('STEP_ONE')\nraise ValueError('boom')");
        assertAll(
                () -> assertTrue(out.contains("STEP_ONE"), "应保留失败前输出: " + out),
                () -> assertTrue(out.contains("ValueError") || out.contains("boom"), out));
    }

    @Test
    @DisplayName("每次调用是全新解释器：上一次的变量不应残留")
    void executionsAreIsolated() {
        assumeTrue(ready, "需要 GraalVM");
        tools.runPython("LEAKED = 'from_previous_call'");
        String out = tools.runPython("print('LEAKED' in dir())");
        assertTrue(out.contains("False"), "状态不应跨调用泄漏: " + out);
    }

    @Test
    @DisplayName("标准库应可用（描述中承诺了这些模块）")
    void standardLibraryAvailable() {
        assumeTrue(ready, "需要 GraalVM");
        String out = tools.runPython(
                "import json, re, statistics, hashlib, datetime, itertools, base64, textwrap, decimal\n"
                        + "print(statistics.median([3,1,2]))\n"
                        + "print(hashlib.md5(b'x').hexdigest()[:6])");
        assertAll(
                () -> assertTrue(out.contains("✅"), "承诺的模块必须都能导入: " + out),
                () -> assertTrue(out.contains("2"), out));
    }

    @Test
    @DisplayName("文件写入应被拒绝（描述中声明了无磁盘访问）")
    void fileWriteDenied() {
        assumeTrue(ready, "需要 GraalVM");
        String out = tools.runPython("open('x.txt','w').write('1')\nprint('WROTE')");
        assertTrue(!out.contains("WROTE"), "不应能写文件: " + out);
    }
}
