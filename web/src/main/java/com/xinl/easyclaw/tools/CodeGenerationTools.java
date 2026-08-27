package com.xinl.easyclaw.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CodeGenerationTools {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationTools.class);

    /**
     * Java 方法声明的近似匹配。
     *
     * <p>注意：必须要求至少一个真实修饰符或返回类型标识，不能把 {@code \s} 放进
     * 交替分支——否则 {@code if (}、{@code for (}、{@code while (}、{@code new Foo(}
     * 都会被计为方法，导致统计值虚高。控制流关键字另由 {@link #CONTROL_KEYWORDS} 排除。
     */
    private static final java.util.regex.Pattern JAVA_METHOD = java.util.regex.Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|private|protected|static|final|abstract|synchronized|native|strictfp|default)[ \\t]+)+"
                    + "[\\w<>\\[\\],.?& \\t]+?[ \\t]+(\\w+)[ \\t]*\\(");

    /** 控制流关键字：即便侥幸匹配上方模式也不算方法。 */
    private static final java.util.Set<String> CONTROL_KEYWORDS =
            java.util.Set.of("if", "for", "while", "switch", "catch", "synchronized", "return", "new", "do", "else");

    private static final java.util.regex.Pattern JAVA_TYPE = java.util.regex.Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|private|protected|static|final|abstract|sealed|non-sealed)[ \\t]+)*"
                    + "(class|interface|enum|record)[ \\t]+(\\w+)");

    private final PythonCodeAnalyzer pythonAnalyzer;

    public CodeGenerationTools(PythonCodeAnalyzer pythonAnalyzer) {
        this.pythonAnalyzer = pythonAnalyzer;
    }

    @Tool(name = "analyze_code", description = "统计代码结构指标：行数（区分代码/注释/空行）、函数数、类数、嵌套深度、导入模块。\n"
            + "【何时用】需要快速了解某段代码的规模与结构（如代码评审、生成摘要、估算改动面）时调用。\n"
            + "【精度】Python 代码走 AST 精确解析，并给出函数/类清单与行号；其他语言为正则近似统计。\n"
            + "【不要用于】读取文件（先用 read_file 把内容读入，再传入 code）；跨文件依赖分析。\n"
            + "【参数】code：完整代码文本；language：语言名（Java/Python/JavaScript...），填 Python 可获得最高精度。")
    public String analyzeCode(@ToolParam(name = "code", description = "要分析的完整代码文本（不是文件路径）") String code,
                              @ToolParam(name = "language", description = "编程语言名，如 Java、Python、JavaScript") String language) {
        if (code == null || code.isBlank()) {
            return "❌ 代码内容为空";
        }
        log.info("分析代码: language={}, length={}", language, code.length());

        // Python 代码优先走 AST 精确解析
        if (isPython(language) && pythonAnalyzer.isAvailable()) {
            try {
                return pythonAnalyzer.analyzePython(code);
            } catch (IllegalArgumentException e) {
                // 语法错误是用户输入问题，直接告知，不必降级（降级也只会给出错误数字）
                return "❌ Python 代码解析失败: " + e.getMessage();
            } catch (RuntimeException e) {
                log.warn("Python 分析器执行失败，降级为通用统计: {}", e.getMessage());
            }
        }
        return analyzeGeneric(code, language);
    }

    private static boolean isPython(String language) {
        if (language == null) {
            return false;
        }
        String l = language.trim().toLowerCase();
        return l.equals("python") || l.equals("py") || l.equals("python3");
    }

    /** 通用（正则近似）统计，用于非 Python 语言或 Python 引擎不可用时。 */
    private String analyzeGeneric(String code, String language) {
        try {
            String[] lines = code.split("\n", -1);
            long blank = 0;
            long comment = 0;
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty()) {
                    blank++;
                } else if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("#")) {
                    comment++;
                }
            }

            long methodCount = JAVA_METHOD.matcher(code).results()
                    .filter(r -> !CONTROL_KEYWORDS.contains(r.group(1)))
                    .count();
            long classCount = JAVA_TYPE.matcher(code).results().count();

            StringBuilder result = new StringBuilder();
            result.append("📊 代码分析结果（正则近似统计）:\n");
            result.append("- 语言: ").append(language == null ? "未指定" : language).append('\n');
            result.append("- 总行数: ").append(lines.length);
            result.append("（代码 ").append(lines.length - blank - comment);
            result.append(" / 注释 ").append(comment);
            result.append(" / 空行 ").append(blank).append("）\n");
            result.append("- 字符数: ").append(code.length()).append('\n');
            result.append("- 方法数: ").append(methodCount).append('\n');
            result.append("- 类/接口/枚举/记录数: ").append(classCount).append('\n');
            if (isPython(language)) {
                result.append("- ⚠️ 说明: Python AST 引擎不可用（需 GraalVM 运行时），当前为近似统计\n");
            }
            return result.toString();
        } catch (Exception e) {
            log.error("代码分析失败: {}", e.getMessage());
            return "❌ 代码分析失败: " + e.getMessage();
        }
    }

    @Tool(name = "format_code", description = "对传入的代码文本做基础格式化：压缩连续空行、去除行尾空白。\n"
            + "【何时用】仅用于快速清理粘贴/拼接的代码，便于阅读。\n"
            + "【不要用于】按项目风格正式格式化文件——优先用 edit_file 配合项目的格式化工具或构建脚本（如 mvn spotless、prettier），本工具不做语法感知格式化。\n"
            + "【参数】code：要格式化的完整代码文本；返回结果需自行写回文件。")
    public String formatCode(@ToolParam(name = "code", description = "要格式化的完整代码文本（不是文件路径）") String code) {
        log.info("格式化代码: length={}", code.length());
        try {
            String formatted = code
                    .replaceAll("[ \\t]+\\n", "\n")
                    .replaceAll("\\n{3,}", "\n\n")
                    .replaceAll("[ \\t]+$", "")
                    .trim();

            return "格式化后的代码:\n```\n" + formatted + "\n```";
        } catch (Exception e) {
            log.error("代码格式化失败: {}", e.getMessage());
            return "❌ 代码格式化失败: " + e.getMessage();
        }
    }

    @Tool(name = "diff_code", description = "对比两段代码，输出 unified diff 格式的真实差异，并给出新增/删除行数与相似度。\n"
            + "【何时用】比较某个文件的修改前/后版本、或两份实现的差异时调用。\n"
            + "【精度】基于 LCS 序列匹配，开头插入/删除行不会造成后续全文错位误报。\n"
            + "【不要用于】对比两个文件——先把两份内容分别读入再传入 code1/code2；需要完整 git diff 时用 execute(\"git diff ...\")。\n"
            + "【参数】code1：旧代码/基准代码；code2：新代码/对比代码；完全相同会返回 ✅。")
    public String diffCode(@ToolParam(name = "code1", description = "第一段代码（基准）") String code1,
                          @ToolParam(name = "code2", description = "第二段代码（对比）") String code2) {
        String a = code1 == null ? "" : code1;
        String b = code2 == null ? "" : code2;
        log.info("代码对比: code1={}, code2={}", a.length(), b.length());

        if (pythonAnalyzer.isAvailable()) {
            try {
                return pythonAnalyzer.unifiedDiff(a, b);
            } catch (RuntimeException e) {
                log.warn("Python diff 执行失败，降级为逐行对比: {}", e.getMessage());
            }
        }
        return diffGeneric(a, b);
    }

    /**
     * 逐行下标对齐的简易对比，仅在 Python 引擎不可用时使用。
     *
     * <p>已知局限：插入或删除行会导致其后所有行错位并被报为差异，
     * 因此输出中显式标注该局限，避免调用方误读。
     */
    private String diffGeneric(String code1, String code2) {
        try {
            if (code1.equals(code2)) {
                return "✅ 两段代码完全相同";
            }
            String[] lines1 = code1.split("\n", -1);
            String[] lines2 = code2.split("\n", -1);

            StringBuilder diff = new StringBuilder();
            diff.append("📝 代码差异对比（逐行近似，Python 引擎不可用）:\n");
            diff.append("⚠️ 此模式下插入/删除行会导致后续行错位误报，仅供粗略参考\n\n");

            int maxLen = Math.max(lines1.length, lines2.length);
            int diffCount = 0;

            for (int i = 0; i < maxLen; i++) {
                String line1 = i < lines1.length ? lines1[i] : "";
                String line2 = i < lines2.length ? lines2[i] : "";

                if (!line1.equals(line2)) {
                    diffCount++;
                    diff.append(String.format("行 %d 不同:%n", i + 1));
                    if (i < lines1.length) {
                        diff.append("- ").append(line1).append('\n');
                    }
                    if (i < lines2.length) {
                        diff.append("+ ").append(line2).append('\n');
                    }
                    if (diffCount >= 20) {
                        diff.append("\n... 还有更多差异，仅显示前 20 处\n");
                        break;
                    }
                }
            }
            return diff.toString();
        } catch (Exception e) {
            log.error("代码对比失败: {}", e.getMessage());
            return "❌ 代码对比失败: " + e.getMessage();
        }
    }
}