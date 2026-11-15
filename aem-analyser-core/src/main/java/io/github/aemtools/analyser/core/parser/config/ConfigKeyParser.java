package io.github.aemtools.analyser.core.parser.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Separates the base semantic key from an RFC 724 type hint
 * ({@code scheduledpurge.daysold:Integer} -> {@code scheduledpurge.daysold} + {@code Integer}).
 * Keys starting with ':' (RFC 724 comments / Felix system metadata) are preserved untouched.
 */
public final class ConfigKeyParser {

    private static final Pattern RFC724_TYPED_KEY_PATTERN =
            Pattern.compile("^([^:].*?)(?::([A-Za-z]+(?:\\[\\])?))$");

    private ConfigKeyParser() {
    }

    public record ParsedPropertyKey(String baseKey, String rawKey, String typeHint) {

        public boolean hasTypeHint() {
            return typeHint != null;
        }
    }

    public static ParsedPropertyKey parseKey(String rawKey) {
        if (rawKey == null || rawKey.isEmpty() || rawKey.startsWith(":")) {
            return new ParsedPropertyKey(rawKey, rawKey, null);
        }
        Matcher matcher = RFC724_TYPED_KEY_PATTERN.matcher(rawKey);
        if (matcher.matches()) {
            return new ParsedPropertyKey(matcher.group(1), rawKey, matcher.group(2));
        }
        return new ParsedPropertyKey(rawKey, rawKey, null);
    }

    /**
     * Detects Cloud Manager environment / secret variable placeholders:
     * {@code $[env:VAR]}, {@code $[env:VAR;default=...]} and {@code $[secret:KEY]}.
     */
    public static boolean isEnvPlaceholder(String value) {
        return value != null
                && (value.startsWith("$[env:") || value.startsWith("$[secret:") || value.startsWith("$[property:"));
    }

    /**
     * Extracts the default value from a Cloud Manager placeholder:
     * {@code $[env:API_URL;default=https://api.example.com:8080]} -> {@code https://api.example.com:8080}.
     */
    public static String extractPlaceholderDefault(String value) {
        if (!isEnvPlaceholder(value)) {
            return value;
        }
        int defaultIdx = value.indexOf(";default=");
        if (defaultIdx < 0) {
            return value;
        }
        String rest = value.substring(defaultIdx + ";default=".length());
        return rest.endsWith("]") ? rest.substring(0, rest.length() - 1) : rest;
    }
}
