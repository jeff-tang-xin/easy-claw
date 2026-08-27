package com.xinl.easyclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.python.GraalPyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 代码分析的 Python 增强实现。
 *
 * <p>职责：把 {@link GraalPyEngine} 返回的 JSON 转成给模型看的文本。
 * 引擎不可用（非 GraalVM 环境）或执行出错时抛异常，由调用方决定降级策略——
 * 本类不做兜底，保证「要么给出精确结果，要么明确失败」，不产出似是而非的数据。
 */
@Component
public class PythonCodeAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(PythonCodeAnalyzer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GraalPyEngine engine;

    public PythonCodeAnalyzer(GraalPyEngine engine) {
        this.engine = engine;
    }

    public boolean isAvailable() {
        return engine.isAvailable();
    }

    /**
     * 用 AST 分析 Python 代码。
     *
     * @return 面向模型的可读文本
     * @throws IllegalStateException 引擎不可用
     * @throws IllegalArgumentException 代码有语法错误
     */
    public String analyzePython(String code) {
        JsonNode root = parse(engine.call("analyze_python", code));
        if (!root.path("ok").asBoolean()) {
            throw new IllegalArgumentException(root.path("error").asText("未知错误"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 代码分析结果（AST 精确解析）:\n");
        sb.append("- 语言: ").append(root.path("language").asText()).append('\n');
        sb.append("- 总行数: ").append(root.path("totalLines").asInt());
        sb.append("（代码 ").append(root.path("codeLines").asInt());
        sb.append(" / 注释 ").append(root.path("commentLines").asInt());
        sb.append(" / 空行 ").append(root.path("blankLines").asInt()).append("）\n");
        sb.append("- 字符数: ").append(root.path("chars").asInt()).append('\n');
        sb.append("- 函数数: ").append(root.path("functionCount").asInt()).append('\n');
        sb.append("- 类数: ").append(root.path("classCount").asInt()).append('\n');
        sb.append("- 最大嵌套深度: ").append(root.path("maxNestingDepth").asInt()).append('\n');

        JsonNode imports = root.path("imports");
        if (imports.isArray() && !imports.isEmpty()) {
            sb.append("- 导入模块: ");
            for (int i = 0; i < imports.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(imports.get(i).asText());
            }
            sb.append('\n');
        }

        JsonNode functions = root.path("functions");
        if (functions.isArray() && !functions.isEmpty()) {
            sb.append("\n函数清单:\n");
            for (JsonNode fn : functions) {
                sb.append("  - ").append(fn.path("name").asText());
                sb.append("(").append(fn.path("args").asInt()).append(" 参数)");
                sb.append(" @行 ").append(fn.path("line").asInt());
                if (fn.path("isAsync").asBoolean()) {
                    sb.append(" [async]");
                }
                sb.append('\n');
            }
        }

        JsonNode classes = root.path("classes");
        if (classes.isArray() && !classes.isEmpty()) {
            sb.append("\n类清单:\n");
            for (JsonNode cls : classes) {
                sb.append("  - ").append(cls.path("name").asText());
                sb.append(" @行 ").append(cls.path("line").asInt()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 用 difflib 生成真实差异（LCS 匹配，不会因插入行导致后续错位误报）。
     *
     * @throws IllegalStateException 引擎不可用
     */
    public String unifiedDiff(String code1, String code2) {
        JsonNode root = parse(engine.call("unified_diff", code1, code2, "before", "after", 3));
        if (!root.path("ok").asBoolean()) {
            throw new IllegalArgumentException(root.path("error").asText("未知错误"));
        }

        if (root.path("identical").asBoolean()) {
            return "✅ 两段代码完全相同";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📝 代码差异对比（difflib 真实差异）:\n");
        sb.append("- 新增行: ").append(root.path("added").asInt()).append('\n');
        sb.append("- 删除行: ").append(root.path("removed").asInt()).append('\n');
        sb.append("- 相似度: ")
                .append(String.format("%.2f%%", root.path("similarity").asDouble() * 100))
                .append('\n');
        if (root.path("truncated").asBoolean()) {
            sb.append("- ⚠️ 差异过长，仅显示前 400 行（共 ")
                    .append(root.path("totalDiffLines").asInt())
                    .append(" 行）\n");
        }
        sb.append('\n').append(root.path("diff").asText());
        return sb.toString();
    }

    private JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            log.error("解析 Python 返回的 JSON 失败: {}", abbreviate(json), e);
            throw new IllegalStateException("Python 返回内容无法解析: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
