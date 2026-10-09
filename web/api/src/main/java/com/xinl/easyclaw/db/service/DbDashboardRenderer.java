package com.xinl.easyclaw.db.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.db.service.DbConnectionService.DbSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据看板渲染器（V33）：blocks JSON → 逐块执行 SQL → 拼装离线可用 HTML。
 * <p>
 * 区块四类型（约定见 db_dashboard 工具描述）：
 * <ul>
 *   <li>{@code kpi}：SQL 返回单值（取第一数据行第一列）；</li>
 *   <li>{@code bar}：SQL 返回两列（label, value），最多 20 条，宽度按最大值归一；</li>
 *   <li>{@code table}：任意列，Markdown 表格转 HTML，超长由 {@link DbQueryExecutor} 截断；</li>
 *   <li>{@code note}：纯文本口径说明，无 SQL。</li>
 * </ul>
 * 防线：每块 SQL 走 {@link DbQueryExecutor} 完整编排（guard → 审计 → 执行 → 脱敏渲染），
 * 不绕过只读防线；块级 try-catch——单块失败只渲染该块错误卡片，其余照常。
 * 所有动态文本经 HTML 转义后输出（iframe sandbox=allow-scripts 下脚本仍可执行，转义是硬要求）。
 */
@Component
public class DbDashboardRenderer {

    private static final Logger log = LoggerFactory.getLogger(DbDashboardRenderer.class);

    /** bar 块最多渲染的条数（超出截断并标注） */
    private static final int MAX_BARS = 20;

    private final DbConnectionService db;
    private final DbQueryExecutor queryExecutor;
    private final ObjectMapper objectMapper;

    public DbDashboardRenderer(DbConnectionService db, DbQueryExecutor queryExecutor, ObjectMapper objectMapper) {
        this.db = db;
        this.queryExecutor = queryExecutor;
        this.objectMapper = objectMapper;
    }

    /** 单个区块定义（blocks JSON 数组元素） */
    public record Block(String type, String label, String sql, String text, String connKey) {
    }

    /**
     * 渲染看板：解析 blocks → 逐块执行 → 拼完整 HTML 文档。
     *
     * @param workspaceId    工作区（连接路由作用域）
     * @param defaultConnKey 保存看板时的来源连接（区块未指定 connKey 时的缺省路由）
     * @param blocksJson     区块清单 JSON
     * @return 完整 HTML 文档（前端 iframe srcDoc 直接渲染）
     */
    public String render(String workspaceId, String defaultConnKey, String blocksJson) {
        List<Block> blocks = parseBlocks(blocksJson);
        StringBuilder body = new StringBuilder();
        for (Block b : blocks) {
            body.append(renderBlock(workspaceId, defaultConnKey, b)).append('\n');
        }
        return document(body.toString());
    }

    /** 解析并校验 blocks JSON：数组、每项 type 合法、需要 SQL 的类型必须带非空 sql。 */
    public List<Block> parseBlocks(String blocksJson) {
        if (blocksJson == null || blocksJson.isBlank()) {
            throw new IllegalArgumentException("blocks 不能为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(blocksJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("blocks 不是合法 JSON：" + e.getMessage());
        }
        if (!root.isArray() || root.isEmpty()) {
            throw new IllegalArgumentException("blocks 必须是非空 JSON 数组");
        }
        List<Block> out = new ArrayList<>();
        for (JsonNode n : root) {
            String type = n.path("type").asText("");
            String label = n.path("label").asText("");
            String sql = n.path("sql").asText(null);
            String text = n.path("text").asText(null);
            String connKey = n.path("connKey").asText(null);
            if (!"kpi".equals(type) && !"bar".equals(type) && !"table".equals(type) && !"note".equals(type)) {
                throw new IllegalArgumentException("未知区块类型: " + type + "（合法值 kpi/bar/table/note）");
            }
            if ("note".equals(type)) {
                if (text == null || text.isBlank()) {
                    throw new IllegalArgumentException("note 区块缺少 text");
                }
            } else if (sql == null || sql.isBlank()) {
                throw new IllegalArgumentException(type + " 区块缺少 sql");
            } else if (label == null || label.isBlank()) {
                throw new IllegalArgumentException(type + " 区块缺少 label");
            }
            out.add(new Block(type, label, sql, text, connKey));
        }
        return out;
    }

    /** 渲染单块；任何异常降级为该块的错误卡片（块级隔离）。 */
    private String renderBlock(String workspaceId, String defaultConnKey, Block b) {
        try {
            return switch (b.type()) {
                case "note" -> note(b.label(), b.text());
                case "kpi" -> kpi(workspaceId, defaultConnKey, b);
                case "bar" -> bar(workspaceId, defaultConnKey, b);
                case "table" -> table(workspaceId, defaultConnKey, b);
                default -> error(b.label(), "未知类型 " + b.type());
            };
        } catch (Exception e) {
            log.warn("看板区块渲染失败: type={}, label={}, err={}", b.type(), b.label(), e.getMessage());
            return error(b.label(), e.getMessage());
        }
    }

    // ==================== 各类型渲染 ====================

    private String kpi(String workspaceId, String defaultConnKey, Block b) {
        String md = runSql(workspaceId, defaultConnKey, b);
        List<List<String>> rows = parseMarkdownTable(md);
        String value = rows.isEmpty() || rows.get(0).isEmpty() ? "—" : rows.get(0).get(0);
        return "<div class=\"db-kpi\"><div class=\"db-kpi-label\">" + esc(b.label())
                + "</div><div class=\"db-kpi-value\">" + esc(value) + "</div></div>";
    }

    private String bar(String workspaceId, String defaultConnKey, Block b) {
        String md = runSql(workspaceId, defaultConnKey, b);
        List<List<String>> rows = parseMarkdownTable(md);
        if (rows.isEmpty()) {
            return section(b.label(), "<div class=\"db-empty\">0 rows</div>");
        }
        double max = 0;
        List<double[]> unused = new ArrayList<>();
        List<String[]> items = new ArrayList<>();
        for (List<String> r : rows) {
            String label = r.get(0);
            double v = 0;
            try {
                v = Double.parseDouble(r.get(1).replace(",", ""));
            } catch (Exception ignore) {
                // 非数值按 0 处理，仍展示标签
            }
            unused.add(new double[]{v});
            max = Math.max(max, v);
            items.add(new String[]{label, r.get(1)});
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(items.size(), MAX_BARS);
        for (int i = 0; i < shown; i++) {
            double v = unused.get(i)[0];
            int pct = max <= 0 ? 0 : (int) Math.round(v * 100.0 / max);
            sb.append("<div class=\"db-bar-row\"><span class=\"db-bar-label\">").append(esc(items.get(i)[0]))
                    .append("</span><div class=\"db-bar-track\"><div class=\"db-bar-fill\" style=\"width:").append(pct)
                    .append("%\"></div></div><span class=\"db-bar-val\">").append(esc(items.get(i)[1]))
                    .append("</span></div>");
        }
        String more = items.size() > MAX_BARS ? "<div class=\"db-more\">仅显示前 " + MAX_BARS + " 条（共 "
                + items.size() + " 条）</div>" : "";
        return section(b.label(), "<div class=\"db-bars\">" + sb + "</div>" + more);
    }

    private String table(String workspaceId, String defaultConnKey, Block b) {
        String md = runSql(workspaceId, defaultConnKey, b);
        List<List<String>> rows = parseMarkdownTable(md);
        if (rows.isEmpty()) {
            return section(b.label(), "<div class=\"db-empty\">0 rows</div>");
        }
        StringBuilder sb = new StringBuilder("<table class=\"db-table\"><thead><tr>");
        for (String h : rows.get(0)) {
            sb.append("<th>").append(esc(h)).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (int i = 1; i < rows.size(); i++) {
            sb.append("<tr>");
            for (String c : rows.get(i)) {
                sb.append("<td>").append(esc(c)).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        return section(b.label(), sb.toString());
    }

    private String note(String label, String text) {
        return "<div class=\"db-note\">" + (label == null || label.isBlank() ? "" : "<b>" + esc(label) + "</b>　")
                + esc(text) + "</div>";
    }

    private String error(String label, String msg) {
        return "<div class=\"db-block-error\"><b>⚠ " + esc(label == null ? "" : label)
                + "</b><div class=\"db-block-error-msg\">" + esc(msg == null ? "未知错误" : msg) + "</div></div>";
    }

    private String section(String label, String inner) {
        return "<div class=\"db-block\"><div class=\"db-block-title\">" + esc(label) + "</div>" + inner + "</div>";
    }

    // ==================== 执行与解析 ====================

    /** 路由到区块指定连接（缺省保存时的连接）并执行只读 SQL，返回 Markdown 表格。 */
    private String runSql(String workspaceId, String defaultConnKey, Block b) {
        String connKey = b.connKey() == null || b.connKey().isBlank() ? defaultConnKey : b.connKey();
        DbSession session = db.session(workspaceId, connKey);
        if (session == null) {
            throw new IllegalStateException("连接不存在或已断开: " + connKey);
        }
        return queryExecutor.executeQuery(session, b.sql(), "dashboard");
    }

    /**
     * 解析 executeQuery 产出的 Markdown 表格为行列文本。
     * 约定输入格式：首行表头、次行分隔（|---|---|）、其后数据行；单元格以 | 分隔。
     * 解析失败（非表格输出，如错误文案）时抛异常，由块级隔离呈现。
     */
    private List<List<String>> parseMarkdownTable(String md) {
        List<List<String>> out = new ArrayList<>();
        if (md == null || md.isBlank()) {
            return out;
        }
        for (String line : md.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                if (out.isEmpty()) {
                    continue;
                }
                break;
            }
            String[] cells = t.split("\\|", -1);
            List<String> row = new ArrayList<>();
            for (int i = 1; i < cells.length - 1; i++) {
                row.add(cells[i].trim());
            }
            if (row.isEmpty() || row.stream().allMatch(c -> c.matches("[\\s:-]*"))) {
                continue;
            }
            out.add(row);
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("查询结果不是表格：" + (md.length() > 80 ? md.substring(0, 80) + "…" : md));
        }
        return out;
    }

    /** HTML 转义（& < > " '）——所有动态文本入 HTML 前必须经过。 */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 完整 HTML 文档骨架（内联 CSS，离线可用，无任何外链）。 */
    private static String document(String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>"
                + "body{font-family:-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;"
                + "margin:0;padding:16px;background:#f5f7fa;color:rgba(0,0,0,0.88);font-size:14px;}"
                + ".db-kpi{display:inline-block;min-width:160px;margin:0 16px 16px 0;padding:16px 20px;"
                + "background:#fff;border:1px solid #f0f0f0;border-radius:8px;"
                + "box-shadow:0 1px 2px rgba(0,0,0,0.03);}"
                + ".db-kpi-label{font-size:12px;color:rgba(0,0,0,0.45);margin-bottom:8px;}"
                + ".db-kpi-value{font-size:28px;font-weight:600;font-variant-numeric:tabular-nums;color:#1677ff;}"
                + ".db-block{background:#fff;border:1px solid #f0f0f0;border-radius:8px;padding:16px;margin-bottom:16px;}"
                + ".db-block-title{font-size:13px;font-weight:600;margin-bottom:12px;color:rgba(0,0,0,0.65);}"
                + ".db-bar-row{display:flex;align-items:center;gap:8px;margin-bottom:6px;}"
                + ".db-bar-label{width:180px;font-size:12px;color:rgba(0,0,0,0.65);text-align:right;"
                + "white-space:nowrap;overflow:hidden;text-overflow:ellipsis;flex:none;}"
                + ".db-bar-track{flex:1;height:16px;background:#f5f5f5;border-radius:4px;overflow:hidden;}"
                + ".db-bar-fill{height:100%;background:#1677ff;border-radius:4px;min-width:2px;}"
                + ".db-bar-val{width:90px;font-size:12px;color:rgba(0,0,0,0.65);font-variant-numeric:tabular-nums;flex:none;}"
                + ".db-table{border-collapse:collapse;width:100%;font-size:12.5px;}"
                + ".db-table th{background:#fafafa;text-align:left;padding:8px 12px;border:1px solid #f0f0f0;}"
                + ".db-table td{padding:8px 12px;border:1px solid #f0f0f0;font-variant-numeric:tabular-nums;}"
                + ".db-table tbody tr:nth-child(even){background:#fafafa;}"
                + ".db-note{font-size:12.5px;color:rgba(0,0,0,0.45);line-height:1.6;}"
                + ".db-empty{color:rgba(0,0,0,0.45);font-size:12.5px;}"
                + ".db-more{font-size:12px;color:rgba(0,0,0,0.45);margin-top:8px;}"
                + ".db-block-error{background:#fff2f0;border:1px solid #ffccc7;border-radius:8px;"
                + "padding:12px 16px;margin-bottom:16px;color:#cf1322;font-size:13px;}"
                + ".db-block-error-msg{margin-top:6px;font-size:12px;color:rgba(0,0,0,0.45);word-break:break-all;}"
                + "</style></head><body>" + body + "</body></html>";
    }
}
