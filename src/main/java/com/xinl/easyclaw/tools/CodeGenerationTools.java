package com.xinl.easyclaw.tools;

import com.embabel.agent.api.annotation.LlmTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CodeGenerationTools {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationTools.class);

    @LlmTool(description = "对代码进行静态分析，统计行数、方法数、类数等基本指标。不执行代码，只做正则匹配。")
    public String analyzeCode(
            @LlmTool.Param(description = "要分析的源代码文本")
            String code,
            @LlmTool.Param(description = "编程语言，如 java、python、javascript 等")
            String language) {
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

    @LlmTool(description = "清理代码中的多余空白行和行尾空格，美化格式。返回格式化后的代码（用 Markdown 代码块包裹）。")
    public String formatCode(
            @LlmTool.Param(description = "要格式化的源代码文本")
            String code) {
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

    @LlmTool(description = "逐行对比两段代码的差异。返回前 20 处不同，格式为 diff 风格（- 表示删除行，+ 表示新增行）。")
    public String diffCode(
            @LlmTool.Param(description = "原始代码文本")
            String code1,
            @LlmTool.Param(description = "修改后的代码文本")
            String code2) {
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
