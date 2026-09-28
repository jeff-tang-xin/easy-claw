package com.xinl.easyclaw.ops.service;

import java.util.regex.Pattern;

/**
 * 终端输出脱敏：在终端内容进入 LLM 上下文（AI 工具返回等）之前，
 * 掩掉常见凭证形态，降低敏感信息出网风险。
 *
 * <p>覆盖：password/token/secret/api_key 等赋值形式、URL 内嵌凭证、
 * Authorization 头、JWT、AWS AccessKey、PEM 私钥块。
 *
 * <p>边界：正则只能覆盖常见形态，无法保证 100%；命令行内联密码
 * （如 {@code mysql -pXXX}、{@code sshpass -p}）因误伤率高不做处理。
 */
public final class TerminalOutputSanitizer {

    private TerminalOutputSanitizer() {
    }

    /** key=value / key: value 形式的凭证赋值（保留 key，值掩掉） */
    private static final Pattern CREDENTIAL_KV = Pattern.compile(
            "(?i)\\b((?:password|passwd|pass|token|secret|api[_-]?key|access[_-]?key|"
                    + "private[_-]?key|auth)(?:\\s*[=:]\\s*))(\"?)([^\\s\"']{3,})");

    /** URL 内嵌凭证 scheme://user:pass@host（用户名可空，如 redis://:pass@host） */
    private static final Pattern URL_CREDENTIAL = Pattern.compile(
            "(\\w+://[^:/\\s@]*:)([^@\\s/]+)(@)");

    /** Authorization: Bearer/Basic xxx */
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)((?:authorization|proxy-authorization)\\s*:\\s*(?:bearer|basic)\\s+)(\\S+)");

    /** JWT 三段式 */
    private static final Pattern JWT = Pattern.compile(
            "\\b(eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})\\b");

    /** AWS Access Key ID */
    private static final Pattern AWS_KEY = Pattern.compile("\\b(AKIA[0-9A-Z]{16})\\b");

    /** PEM 私钥块（跨行，整块掩掉） */
    private static final Pattern PEM_KEY = Pattern.compile(
            "(?s)(-----BEGIN [A-Z ]*PRIVATE KEY-----).*?(-----END [A-Z ]*PRIVATE KEY-----)");

    /**
     * 掩掉常见凭证形态；无法识别的形态不处理（正则脱敏只能尽力而为）。
     *
     * @param text 原始终端输出
     * @return 脱敏后的文本（null 安全）
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        out = PEM_KEY.matcher(out).replaceAll("$1 *** $2");
        out = CREDENTIAL_KV.matcher(out).replaceAll("$1$2***");
        out = URL_CREDENTIAL.matcher(out).replaceAll("$1***$3");
        out = AUTH_HEADER.matcher(out).replaceAll("$1***");
        out = JWT.matcher(out).replaceAll("***");
        out = AWS_KEY.matcher(out).replaceAll("***");
        return out;
    }
}
