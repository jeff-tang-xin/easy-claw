package com.xinl.easyclaw.db.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
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

    /** db_dml 允许的语句首词：数据操作语言（改数据，不改结构） */
    public static final List<String> DML_FIRST_WORDS = List.of("insert", "update", "delete", "merge");

    /** db_ddl 允许的语句首词：数据定义语言（comment/rename 覆盖 COMMENT ON / RENAME 语法） */
    public static final List<String> DDL_FIRST_WORDS =
            List.of("create", "alter", "drop", "truncate", "rename", "comment");

    /** MySQL 可执行注释（版本注释）：剥注释后不可见但 MySQL 会真实执行，一律拒绝 */
    private static final Pattern EXECUTABLE_COMMENT = Pattern.compile("/\\*!");

    /** 库级对象语句：删库/建库/改库属性超出「表结构变更」范畴，DDL 工具不放行 */
    private static final Pattern DATABASE_LEVEL = Pattern.compile(
            "\\b(?:drop|alter|create)\\s+(?:database|schema)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern WHERE_CLAUSE = Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 禁止的「代码 / 权限 / 实例级」对象（多方言）：
     * <ul>
     *   <li>function/procedure/trigger/event —— 函数体是任意执行体（体内可藏 DML/DDL/动态 SQL），
     *       创建它等于给 AI 开一条绕过本防线的常驻通道（PG 的 {@code CREATE OR REPLACE} 已兼容）；</li>
     *   <li>user/login/role/group —— 权限面操作（Oracle USER / SQL Server LOGIN,USER / PG ROLE / MySQL USER）；</li>
     *   <li>session/system —— Oracle 会话与实例级篡改（{@code ALTER SESSION SET current_schema} 等）。</li>
     * </ul>
     * 正则要求动词后紧跟对象类型，{@code ALTER TABLE user}（表名恰为 user）不误伤。
     */
    private static final Pattern FORBIDDEN_OBJECTS = Pattern.compile(
            "\\b(?:create|alter|drop)\\s+(?:or\\s+replace\\s+)?"
                    + "(?:user|login|role|group|function|procedure|trigger|event|session|system)\\b",
            Pattern.CASE_INSENSITIVE);

    /** MySQL 的 RENAME USER（权限面操作；RENAME TABLE 是正当表结构变更，放行） */
    private static final Pattern RENAME_USER = Pattern.compile(
            "\\brename\\s+user\\b", Pattern.CASE_INSENSITIVE);

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

    /**
     * 写操作语句校验（db_dml / db_ddl 专用），多方言（MySQL/PG/Oracle/SQL Server）收紧判定：
     * <ol>
     *   <li>MySQL 可执行注释（{@code /*!50000 ...*}{@code /}）：剥注释后不可见但 MySQL 会真实执行，
     *       是「审查看到的」与「实际执行的」不一致的经典绕过向量——原文含即拒
     *       （Oracle 的 {@code /*+ hint *}{@code /} 不含 {@code !}，不误伤）；</li>
     *   <li>单语句：剥字符串后按分号分段（防「UPDATE 后跟分号再跟 DROP」式拼接；
     *       PG dollar-quoting / 双引号字符串内的分号会误伤——方向是更严，可接受）；</li>
     *   <li>语句首词白名单：DML 工具只放 INSERT/UPDATE/DELETE/MERGE，DDL 工具只放
     *       CREATE/ALTER/DROP/TRUNCATE/RENAME/COMMENT——WITH（PG CTE 写）、DO、BEGIN、CALL/EXEC、
     *       LOAD、PREPARE、SET、USE 等一切其他首词一律拒；</li>
     *   <li>库级语句：CREATE/ALTER/DROP DATABASE/SCHEMA（删库/建库/改库属性超出表结构变更范畴）；</li>
     *   <li>代码/权限/实例级对象：FUNCTION/PROCEDURE/TRIGGER/EVENT（函数体=任意执行体）、
     *       USER/LOGIN/ROLE/GROUP（权限面）、SESSION/SYSTEM（Oracle 实例级）；</li>
     *   <li>UPDATE/DELETE 必须带<b>顶层</b> WHERE（防全表改/删；子查询内的 WHERE 不算数——
     *       {@code UPDATE t SET a=(SELECT..WHERE..)} 仍判无 WHERE）。</li>
     * </ol>
     * 本防线是<b>行为层</b>约束；物理防线是 DB 账号权限与「每次执行强制人工确认」
     * （{@code ToolPermissionPolicy.NON_BYPASSABLE}：永久允许也摘不掉确认）。
     *
     * @param allowedFirstWords 允许的语句首词（小写常量清单，比对大小写不敏感）
     * @param label             工具语义标签（DML/DDL），用于拒绝文案
     */
    public Verdict checkWrite(String sql, List<String> allowedFirstWords, String label) {
        if (sql == null || sql.isBlank()) {
            return new Verdict(false, "SQL 为空");
        }
        // 可执行注释必须在剥注释【前】检查原文：stripComments 会把它当普通注释删掉
        if (EXECUTABLE_COMMENT.matcher(sql).find()) {
            return new Verdict(false, "拒绝执行：不允许使用 MySQL 可执行注释（/*! ... */）");
        }
        String noComments = stripComments(sql);
        if (noComments.isBlank()) {
            return new Verdict(false, "SQL 仅含注释");
        }
        String noStrings = stripStrings(noComments);
        String[] segments = noStrings.split(";");
        for (int i = 1; i < segments.length; i++) {
            if (!segments[i].isBlank()) {
                return new Verdict(false, "拒绝执行：一次只允许一条语句（检测到多语句）");
            }
        }
        String firstWord = firstWord(noComments);
        if (firstWord == null || !allowedFirstWords.contains(firstWord)) {
            String allowed = String.join("/", allowedFirstWords.stream()
                    .map(w -> w.toUpperCase(java.util.Locale.ROOT)).toList());
            return new Verdict(false, "拒绝执行：" + label + " 工具仅允许 " + allowed
                    + " 语句，收到 \"" + (firstWord == null ? "?" : firstWord.toUpperCase())
                    + "\"（查询请用 db_query，另一类变更请用对应工具）");
        }
        if (DATABASE_LEVEL.matcher(noStrings).find()) {
            return new Verdict(false, "拒绝执行：不允许通过工具执行库级操作"
                    + "（CREATE/ALTER/DROP DATABASE/SCHEMA）——库的创建与删除请让人在平台完成");
        }
        if (FORBIDDEN_OBJECTS.matcher(noStrings).find() || RENAME_USER.matcher(noStrings).find()) {
            return new Verdict(false, "拒绝执行：不允许通过工具创建/修改/删除"
                    + " 函数、存储过程、触发器、事件、用户、角色等代码与权限对象"
                    + "（其体内可包含任意 SQL）——此类变更请让人评审后手动执行");
        }
        // UPDATE/DELETE 必须带顶层 WHERE：防全表改/删（MySQL 多表 UPDATE 靠 ON 不带 WHERE 的
        // 写法同样被拒——严格优先，拒绝文案给出路）
        if (("update".equals(firstWord) || "delete".equals(firstWord))
                && !hasTopLevelWhere(noStrings)) {
            return new Verdict(false, "拒绝执行：" + firstWord.toUpperCase()
                    + " 缺少 WHERE 子句（防全表改/删）。如确需全表操作，请让用户在 DB 页面手动执行");
        }
        return new Verdict(true, null);
    }

    /**
     * 是否存在括号深度 0 的 WHERE 子句。子查询内的 WHERE 不算——
     * {@code UPDATE t SET a=(SELECT MAX(x) FROM s WHERE id=1)} 主语句仍无 WHERE，判全表更新。
     */
    private static boolean hasTopLevelWhere(String noStrings) {
        Matcher m = WHERE_CLAUSE.matcher(noStrings);
        while (m.find()) {
            int depth = 0;
            for (int i = 0; i < m.start(); i++) {
                char c = noStrings.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
            if (depth == 0) {
                return true;
            }
        }
        return false;
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
