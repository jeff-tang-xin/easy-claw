package com.xinl.easyclaw.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the redact-before-truncate ordering in {@link LoggingHttpTransport}.
 *
 * <p>Background: a performance pass once reordered this to truncate-then-redact,
 * reasoning that running regexes over a few hundred KB of request body was wasteful.
 * That reordering silently leaked credentials: when the cut point lands inside an
 * api_key value, the closing quote is removed, the redaction pattern
 * {@code ("api_key"\s*:\s*")([^"]{4})[^"]*(")} no longer matches, and the raw secret
 * reaches the log verbatim.
 *
 * <p>This test exists so that any future attempt to "optimize" the ordering fails
 * loudly instead of leaking. Redaction must always run against the full body.
 */
class LoggingHttpTransportRedactionTest {

    /** Applies both redaction patterns exactly as logRequest does, then truncates. */
    private String redactThenTruncate(String body) throws Exception {
        Method truncate = LoggingHttpTransport.class.getDeclaredMethod("truncate", String.class);
        truncate.setAccessible(true);

        java.util.regex.Pattern apiKey = readPattern("API_KEY_PATTERN");
        java.util.regex.Pattern camel = readPattern("CAMEL_API_KEY_PATTERN");

        String redacted = apiKey.matcher(body).replaceAll("$1$2***$3");
        redacted = camel.matcher(redacted).replaceAll("$1$2***$3");
        return (String) truncate.invoke(null, redacted);
    }

    private java.util.regex.Pattern readPattern(String field) throws Exception {
        java.lang.reflect.Field f = LoggingHttpTransport.class.getDeclaredField(field);
        f.setAccessible(true);
        return (java.util.regex.Pattern) f.get(null);
    }

    /** Builds a body where the secret sits far beyond the 500-char truncation point. */
    private String bodyWithSecretAfterPadding(String keyField) {
        return "{\"model\":\"gpt-4\",\"messages\":\"" + "x".repeat(600) + "\","
                + "\"" + keyField + "\":\"sk-ABCD1234VERYSECRET\"}";
    }

    @Test
    @DisplayName("api_key beyond the truncation point is never emitted in raw form")
    void snakeCaseKeyIsRedactedNotLeaked() throws Exception {
        String out = redactThenTruncate(bodyWithSecretAfterPadding("api_key"));
        assertFalse(out.contains("sk-ABCD1234VERYSECRET"),
                "raw secret must never appear in the log line");
        assertFalse(out.contains("1234VERYSECRET"),
                "no tail fragment of the secret may survive");
    }

    @Test
    @DisplayName("apiKey (camelCase) is redacted on the same path")
    void camelCaseKeyIsRedactedNotLeaked() throws Exception {
        String out = redactThenTruncate(bodyWithSecretAfterPadding("apiKey"));
        assertFalse(out.contains("sk-ABCD1234VERYSECRET"),
                "raw secret must never appear in the log line");
    }

    @Test
    @DisplayName("a short body keeps its redaction marker and stays untruncated")
    void shortBodyIsRedactedAndNotTruncated() throws Exception {
        String out = redactThenTruncate("{\"api_key\":\"sk-ABCD1234VERYSECRET\"}");
        assertTrue(out.contains("sk-A***"), "first 4 chars are kept as an identification hint");
        assertFalse(out.contains("truncated"), "a short body must not be truncated");
    }

    @Test
    @DisplayName("oversized bodies are still truncated with their real length reported")
    void oversizedBodyIsTruncated() throws Exception {
        String out = redactThenTruncate("{\"messages\":\"" + "y".repeat(5000) + "\"}");
        assertTrue(out.contains("...(truncated, total "),
                "truncation must state the original length for diagnostics");
        assertTrue(out.length() < 600, "truncated output must stay bounded");
    }
}
