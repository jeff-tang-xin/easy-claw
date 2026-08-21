package com.xinl.easyclaw.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CodeGenerationTools {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationTools.class);

    @Tool(name = "analyze_code", description = "统计传入代码片段的复杂度指标：总行数、字符数、方法数、类/接口/枚举数。\n"
            + "【何时用】需要快速了解某段代码的规模与结构（如代码评审、生成摘要、估算改动面）时调用。\n"
            + "【不要用于】读取或分析文件（先用 read_file 把内容读入，再传入 code）；精确 AST/依赖分析（此类基础统计不包含）。\n"
            + "【参数】code：完整代码文本；language：语言名（Java/Python/JavaScript...），仅用于展示。")
    public String analyzeCode(@ToolParam(name = "code", description = "要分析的完整代码文本（不是文件路径）") String code,
                              @ToolParam(name = "language", description = "编程语言名，如 Java、Python、JavaScript") String language) {
        log.info("分析代码: language={}, length={}", language, code.length());
        try {
            StringBuilder result = new StringBuilder();
            result.append("📊 代码分析结果:\n");
            result.append("- 语言: ").append(language).append("\n");
            result.append("- 总行数: ").append(code.lines().count()).append("\n");
            result.append("- 字符数: ").append(code.length()).append("\n");

            long methodCount = java.util.regex.Pattern.compile(
                    "(public|private|protected|static|final|abstract|synchronized|native|strictfp|\\s)+[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\("
            ).matcher(code).results().count();
            result.append("- 方法数: ").append(methodCount).append("\n");

            long classCount = java.util.regex.Pattern.compile(
                    "(class|interface|enum)\\s+\\w+"
            ).matcher(code).results().count();
            result.append("- 类/接口/枚举数: ").append(classCount).append("\n");

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

    @Tool(name = "diff_code", description = "逐行对比两段代码，返回前 20 处差异（按行号用 - / + 标注）。\n"
            + "【何时用】比较某个文件的修改前/后版本、或两份实现的差异时调用。\n"
            + "【不要用于】对比两个文件——先把两份内容分别读入再传入 code1/code2；需要完整 git diff 时用 execute(\"git diff ...\")。\n"
            + "【参数】code1：旧代码/基准代码；code2：新代码/对比代码；完全相同会返回 ✅。")
    public String diffCode(@ToolParam(name = "code1", description = "第一段代码（基准）") String code1,
                          @ToolParam(name = "code2", description = "第二段代码（对比）") String code2) {
        log.info("代码对比: code1={}, code2={}", code1.length(), code2.length());
        try {
            String[] lines1 = code1.split("\n");
            String[] lines2 = code2.split("\n");

            StringBuilder diff = new StringBuilder();
            diff.append("📝 代码差异对比:\n\n");

            int maxLen = Math.max(lines1.length, lines2.length);
            int diffCount = 0;

            for (int i = 0; i < maxLen; i++) {
                String line1 = i < lines1.length ? lines1[i] : "";
                String line2 = i < lines2.length ? lines2[i] : "";

                if (!line1.equals(line2)) {
                    diffCount++;
                    diff.append(String.format("行 %d 不同:\n", i + 1));
                    if (i < lines1.length) {
                        diff.append("- ").append(line1).append("\n");
                    }
                    if (i < lines2.length) {
                        diff.append("+ ").append(line2).append("\n");
                    }
                    if (diffCount >= 20) {
                        diff.append(String.format("\n... 还有更多差异，仅显示前 20 处\n"));
                        break;
                    }
                }
            }

            if (diffCount == 0) {
                return "✅ 两段代码完全相同";
            }

            return diff.toString();
        } catch (Exception e) {
            log.error("代码对比失败: {}", e.getMessage());
            return "❌ 代码对比失败: " + e.getMessage();
        }
    }
}