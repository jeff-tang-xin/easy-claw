package com.xinl.easyclaw.db.service;

import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 查询结果净化器（V30，设计定稿）：脱敏 + 三重截断，产出给 AI 的 Markdown 表格。
 * <p>
 * <b>脱敏后才给 AI</b>：列名命中敏感模式（password/token/secret/api_key/...）的列，
 * 值整体替换为 {@code ***}——模型知道「有一列叫 password」但拿不到值。
 * <p>
 * 三重截断（防上下文爆炸）：最多 {@value #MAX_ROWS} 行、单单元格 {@value #MAX_CELL_CHARS} 字符、
 * 总输出 {@value #MAX_TOTAL_CHARS} 字符。行数上限同时下沉到 JDBC 驱动层（调用方
 * {@code Statement.setMaxRows(MAX_ROWS + 1)}），避免大表全量拉进内存；多出的 1 行用于探测
 * 「是否还有更多」，尾注据此标注 {@code ≥} 而不是谎报总数；
 * 空结果明确 {@code 0 rows}（不产出空表格让模型猜）。
 */
@Component
public class DbResultSanitizer {

    /** 最多返回行数 */
    public static final int MAX_ROWS = 500;
    /** 单单元格最大字符数（超长截断，标注省略） */
    public static final int MAX_CELL_CHARS = 2000;
    /** 渲染后总字符上限（含表头与尾注） */
    public static final int MAX_TOTAL_CHARS = 60_000;

    /** 敏感列名模式（词根匹配，大小写不敏感）：凭据/密钥/证书类 */
    private static final Pattern SENSITIVE_COLUMN = Pattern.compile(
            ".*(password|passwd|pwd|secret|token|api[_-]?key|apikey|access[_-]?key|"
                    + "private[_-]?key|credential|auth).*",
            Pattern.CASE_INSENSITIVE);

    private static final String MASK = "***";

    /**
     * 执行结果 → 给 AI 的 Markdown 文本（banner + 表格 + 行数尾注）。
     * banner 形如 {@code -- mysql 8.0.36 @ prod-mysql/orders_db（只读）}（设计定稿）。
     */
    public String render(String banner, ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> headers = new ArrayList<>(columnCount);
        boolean[] sensitive = new boolean[columnCount];
        for (int i = 1; i <= columnCount; i++) {
            String label = meta.getColumnLabel(i);
            headers.add(label == null || label.isBlank() ? "col" + i : label);
            sensitive[i - 1] = isSensitive(label);
        }
        List<String[]> rows = new ArrayList<>();
        int total = 0;
        boolean truncated = false;
        while (rs.next()) {
            total++;
            if (rows.size() >= MAX_ROWS) {
                truncated = true;
                continue; // 继续数总行数，但不再收集
            }
            String[] row = new String[columnCount];
            for (int i = 1; i <= columnCount; i++) {
                String v = rs.getString(i);
                row[i - 1] = sensitive[i - 1] && v != null
                        ? MASK
                        : truncateCell(v);
            }
            rows.add(row);
        }
        return renderMarkdown(banner, headers, rows, total, truncated);
    }

    /** 空结果/错误场景的明确文案（设计定稿：空结果明确 0 rows，不让模型猜）。 */
    public static String emptyResult(String banner) {
        return banner + "\n\n0 rows";
    }

    private boolean isSensitive(String columnLabel) {
        return columnLabel != null && SENSITIVE_COLUMN.matcher(columnLabel).matches();
    }

    private static String truncateCell(String v) {
        if (v == null) {
            return "NULL";
        }
        if (v.length() <= MAX_CELL_CHARS) {
            return v.replace("\r", "").replace("\n", " ");
        }
        return v.substring(0, MAX_CELL_CHARS) + "…(截断，共 " + v.length() + " 字符)";
    }

    private static String renderMarkdown(String banner, List<String> headers,
                                         List<String[]> rows, int total, boolean truncated) {
        StringBuilder sb = new StringBuilder(banner).append("\n\n");
        if (rows.isEmpty()) {
            sb.append("0 rows");
            return sb.toString();
        }
        sb.append("| ");
        for (String h : headers) {
            sb.append(escapeCell(h)).append(" | ");
        }
        sb.append("\n| ");
        for (int i = 0; i < headers.size(); i++) {
            sb.append("--- | ");
        }
        sb.append("\n");
        int budget = MAX_TOTAL_CHARS - banner.length() - 64;
        for (String[] row : rows) {
            sb.append("| ");
            for (String cell : row) {
                sb.append(escapeCell(cell)).append(" | ");
            }
            sb.append("\n");
            if (sb.length() > budget) {
                truncated = true;
                break;
            }
        }
        // 尾注必须诚实：调用方在驱动侧设了 setMaxRows(MAX_ROWS + 1)，total 达到上限时它只是
        // 行数下限，不能写成「共 N 行」误导模型。
        if (total > MAX_ROWS) {
            sb.append("\n(").append(MAX_ROWS).append(" of ≥").append(total)
                    .append(" rows，已截断——请加 LIMIT 或改用聚合缩小结果集)");
        } else if (truncated) {
            sb.append("\n(").append(rows.size()).append(" of ").append(total)
                    .append("+ rows，已截断（输出长度限制，后续行未展示）)");
        } else {
            sb.append("\n(").append(total).append(" rows)");
        }
        return sb.toString();
    }

    /** Markdown 表格转义：竖线与换行会破坏表格结构。 */
    private static String escapeCell(String v) {
        return v.replace("|", "\\|").replace("\n", " ");
    }
}
