package com.xinl.easyclaw.db.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * SQL 只读防线（V30，设计定稿）：「语句首词白名单 + 危险子句定位匹配」双层判定。
 * <p>
 * 第一层：剥注释后<b>语句首词</b>只允许 SELECT / WITH——UPDATE/DELETE/DDL/EXEC 一律拒绝；
 * 第二层：在剥注释、剥字符串字面量后的 SQL 上做<b>危险子句定位匹配</b>——拦 WITH 内嵌写
 * （PG 的 {@code WITH x AS (DELETE ...) RETURNING}）与 SELECT INTO OUTFILE 类写副作用。
 * 定位规则：危险词前不能是 {@code .}（负向后行断言）——{@code db.update} 这类限定名、
 * {@code t.delete_flag} 这类列名放行；{@code flag_delete} 整词不拆不误伤。
 * <p>
 * 本防线是<b>行为层</b>约束（防 AI 误写），物理防线始终是 hub 配置的 DB 只读账号——
 * 两层独立生效，不互为替代。
 */
@Component
public class DbQueryGuard {

    /** 允许的语句首词（剥注释后，大小写不敏感） */
    private static final List<String> ALLOWED_FIRST_WORDS = List.of("select", "with");

    /**
     * 危险子句定位模式：词前不能是 {@code .}（限定名后缀放行）。
     * 覆盖：DML（insert/update/delete/merge）、DDL（drop/alter/truncate/create）、
     * 权限（grant/revoke）、文件写（into outfile/dumpfile）、附加库（attach/detach）。
     */
    private static final Pattern DANGEROUS_CLAUSE = Pattern.compile(
            "(?<!\\.)\\b(insert|update|delete|merge|drop|alter|truncate|create|grant|revoke|attach|detach)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INTO_OUTFILE = Pattern.compile(
            "\\binto\\s+(outfile|dumpfile)\\b", Pattern.CASE_INSENSITIVE);

    /** 拒绝原因（工具层直接透传给模型，帮助其自我修正）。 */
    public record Verdict(boolean allowed, String reason) {
    }

    /**
     * 判定 SQL 是否允许执行。任何不通过都给出具体原因。
     * 注意：本方法只做静态判定，不执行 SQL、不访问数据库。
     */
    public Verdict check(String sql) {
        if (sql == null || sql.isBlank()) {
            return new Verdict(false, "SQL 为空");
        }
        String noComments = stripComments(sql);
        if (noComments.isBlank()) {
            return new Verdict(false, "SQL 仅含注释");
        }
        String noStrings = stripStrings(noComments);
        // 多语句拒绝：剥字符串后按分号分段，出现第二段非空即视为多语句
        // （末尾单个分号合法；字符串内的分号已在剥字符串时移除）
        String[] segments = noStrings.split(";");
        for (int i = 1; i < segments.length; i++) {
            if (!segments[i].isBlank()) {
                return new Verdict(false, "拒绝执行：一次只允许一条语句（检测到多语句）");
            }
        }
        String firstWord = firstWord(noComments);
        if (firstWord == null || !ALLOWED_FIRST_WORDS.contains(firstWord)) {
            return new Verdict(false, "拒绝执行：仅允许只读查询（SELECT/WITH），收到 \""
                    + (firstWord == null ? "?" : firstWord.toUpperCase()) + "\"");
        }
        var m = DANGEROUS_CLAUSE.matcher(noStrings);
        if (m.find()) {
            return new Verdict(false, "拒绝执行：检测到写操作关键词 \"" + m.group(1)
                    + "\"（只读连接不允许修改数据或结构）");
        }
        if (INTO_OUTFILE.matcher(noStrings).find()) {
            return new Verdict(false, "拒绝执行：SELECT INTO OUTFILE 属于文件写操作");
        }
        return new Verdict(true, null);
    }

    /** 剥离 -- 行注释与块注释（字符串字面量内的 -- 不动，先保护字符串再剥注释）。 */
    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean inString = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (c == '\'' && next == '\'') { // 转义的引号
                    out.append(next);
                    i++;
                } else if (c == '\'') {
                    inString = false;
                }
                continue;
            }
            if (c == '\'' && !inBlockComment) {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 剥离字符串字面量内容（保留占位空白），供危险词定位与多语句检测使用。 */
    private static String stripStrings(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (inString) {
                if (c == '\'' && next == '\'') {
                    i++;
                } else if (c == '\'') {
                    inString = false;
                } else if (c == '\n') {
                    out.append(' ');
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                out.append('\'');
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 语句首词（小写）；无字母开头返回 null。 */
    private static String firstWord(String sql) {
        String trimmed = sql.trim();
        int i = 0;
        while (i < trimmed.length() && !Character.isLetter(trimmed.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < trimmed.length() && Character.isLetterOrDigit(trimmed.charAt(i))) {
            i++;
        }
        if (start == i) {
            return null;
        }
        return trimmed.substring(start, i).toLowerCase();
    }
}
