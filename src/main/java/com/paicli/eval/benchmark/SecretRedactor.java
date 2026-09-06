package com.paicli.eval.benchmark;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative text redaction for benchmark traces and public artifacts. */
public final class SecretRedactor {
    public static final String REDACTED = "[REDACTED]";

    private static final Pattern DATA_IMAGE = Pattern.compile(
            "(?i)(data:image/[a-z0-9.+-]+;base64,)([a-z0-9+/=\\r\\n]{32,})");
    private static final Pattern BASE64_FIELD = Pattern.compile(
            "(?i)(\\\"(?:imageBase64|image_base64|base64)\\\"\\s*:\\s*\\\")"
                    + "([a-z0-9+/=\\r\\n]{32,})(\\\")");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(\\bbearer\\s+)([a-z0-9._~+/=-]{8,})");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)((?:\\\"?[A-Z0-9_]*(?:API[_-]?KEY|ACCESS[_-]?TOKEN|CLIENT[_-]?SECRET)\\\"?"
                    + "|\\\"?apiKey\\\"?)\\s*[:=]\\s*\\\"?)([^\\\"\\s,}]+)(\\\"?)");
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile(
            "(?<![A-Za-z0-9_-])(sk-[A-Za-z0-9_-]{12,})(?![A-Za-z0-9_-])");
    private static final Pattern JWT = Pattern.compile(
            "(?<![A-Za-z0-9_-])(eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})"
                    + "(?![A-Za-z0-9_-])");
    private static final Pattern LONG_BASE64 = Pattern.compile(
            "(?<![A-Za-z0-9+/=])([A-Za-z0-9+/]{128,}={0,2})(?![A-Za-z0-9+/=])");

    private SecretRedactor() {
    }

    public static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String redacted = replaceKeepingEdges(DATA_IMAGE, input);
        redacted = replaceKeepingEdges(BASE64_FIELD, redacted);
        redacted = replaceKeepingPrefix(BEARER, redacted);
        redacted = replaceKeepingEdges(SECRET_ASSIGNMENT, redacted);
        redacted = OPENAI_STYLE_KEY.matcher(redacted).replaceAll(REDACTED);
        redacted = JWT.matcher(redacted).replaceAll(REDACTED);
        return LONG_BASE64.matcher(redacted).replaceAll(REDACTED);
    }

    private static String replaceKeepingPrefix(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output,
                    Matcher.quoteReplacement(matcher.group(1) + REDACTED));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String replaceKeepingEdges(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String suffix = matcher.groupCount() >= 3 ? matcher.group(3) : "";
            matcher.appendReplacement(output,
                    Matcher.quoteReplacement(matcher.group(1) + REDACTED + suffix));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
