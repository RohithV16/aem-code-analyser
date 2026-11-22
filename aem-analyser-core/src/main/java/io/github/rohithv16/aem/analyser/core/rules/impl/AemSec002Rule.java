package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AEM-SEC-002: open redirect / SSRF when request-controlled data reaches a redirect or outbound URL.
 *
 * <p>Scans every Java type (components, servlets and Sling Models included) and treats a redirect
 * target as safe when it is a string literal, a compile-time constant, or a concatenation of those -
 * only request-derived or unknown values are reported.
 *
 * <p>A constant variable needs a constant expression as its initializer (JLS 4.12.4), so a
 * {@code final} declaration is only trusted when its initializer is a literal, a concatenation of
 * literals/constants, or another such constant. {@code final String target =
 * request.getParameter("to")} is a final local, not a constant, and is reported.
 */
public final class AemSec002Rule implements RuleEvaluator {

    private static final Pattern FINAL_STRING_DECLARATION = Pattern.compile(
            "(?:static\\s+)?final\\s+String\\s+([A-Za-z_$][\\w$]*)\\s*=");

    /** Every {@code String} declaration, final or not: these initializers feed the taint checks. */
    private static final Pattern STRING_DECLARATION = Pattern.compile(
            "\\bString\\s+([A-Za-z_$][\\w$]*)\\s*=");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");

    /** Text that identifies request-controlled input in an expression or in its initializer. */
    private static final List<String> REQUEST_MARKERS = List.of(
            "request.", "getparameter", "getheadervalues", "getheader", "getquerystring", "getpathinfo");

    /** Guards against cycles such as {@code a = b; b = a}. */
    private static final int MAX_TAINT_DEPTH = 4;

    @Override
    public String getRuleId() {
        return RuleId.AEM_SEC_002.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (Path file : context.javaTypeFiles()) {
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(file);
                Map<String, String> constants = collectInitializers(lines, FINAL_STRING_DECLARATION);
                Map<String, String> initializers = collectInitializers(lines, STRING_DECLARATION);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String lower = line.toLowerCase(Locale.ROOT);
                    int redirect = lower.indexOf("sendredirect(");
                    if (redirect >= 0) {
                        String argument = argumentOf(line, redirect + "sendredirect(".length());
                        if (!isConstantExpression(argument, constants)) {
                            violations.add(violation(context, file, i, "OPEN_REDIRECT",
                                    "Redirect target is not a constant and may be user-controlled."));
                        }
                        continue;
                    }
                    int outboundUrl = lower.indexOf("new url(");
                    if (outboundUrl >= 0) {
                        String argument = argumentOf(line, outboundUrl + "new url(".length());
                        if (!isConstantExpression(argument, constants)
                                && containsRequestInput(argument, initializers, 0)) {
                            violations.add(violation(context, file, i, "SSRF",
                                    "Outbound URL is built from request-controlled input."));
                        }
                    }
                }
            } catch (IOException ignored) {
                // unreadable file: diagnostics handled by the parser
            }
        }
        return violations;
    }

    /** Declared name to initializer expression for every declaration the pattern matches. */
    private static Map<String, String> collectInitializers(List<String> lines, Pattern pattern) {
        Map<String, String> initializers = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                initializers.put(matcher.group(1), initializerAfter(line, matcher.end()));
            }
        }
        return initializers;
    }

    /** The initializer expression after {@code =}, stopping at ';' or a line comment. */
    private static String initializerAfter(String line, int from) {
        StringBuilder value = new StringBuilder();
        boolean inString = false;
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                value.append(c);
                if (c == '\\' && i + 1 < line.length()) {
                    value.append(line.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                value.append(c);
            } else if (c == ';') {
                break;
            } else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            } else {
                value.append(c);
            }
        }
        return value.toString().strip();
    }

    /** Extracts the first argument of the call starting at {@code from}, honouring nested parens. */
    private static String argumentOf(String line, int from) {
        int depth = 1;
        boolean inString = false;
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return line.substring(from, i).strip();
                }
            }
        }
        return line.substring(Math.min(from, line.length())).strip();
    }

    /**
     * A redirect target is safe when it is a literal, a known constant (simple or qualified), or a
     * concatenation of those. Anything reaching a call (e.g. {@code request.getParameter("to")}) or
     * an unknown variable is treated as user-controlled.
     */
    private static boolean isConstantExpression(String expression, Map<String, String> constants) {
        return isConstantExpression(expression, constants, new HashSet<>());
    }

    private static boolean isConstantExpression(String expression, Map<String, String> constants,
                                                Set<String> visiting) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        boolean sawToken = false;
        for (String part : splitConcatenation(expression)) {
            String token = part.strip();
            if (token.isEmpty() || !isConstantToken(token, constants, visiting)) {
                return false;
            }
            sawToken = true;
        }
        return sawToken;
    }

    private static boolean isConstantToken(String token, Map<String, String> constants,
                                           Set<String> visiting) {
        if (isStringLiteral(token)) {
            return true;
        }
        boolean qualified = token.indexOf('.') > 0;
        String simpleName = token.substring(token.lastIndexOf('.') + 1).strip();
        if (qualified) {
            // A qualified reference whose member is SCREAMING_CASE is a constant declared in another
            // type (Type.NAME). An UNQUALIFIED screaming-case token may just as well be a local
            // variable holding user input, so it is not treated as constant.
            return isScreamingCase(simpleName);
        }
        if (!simpleName.equals(token) || !IDENTIFIER.matcher(simpleName).matches()) {
            return false;
        }
        if (!visiting.add(simpleName)) {
            return false;
        }
        try {
            String initializer = constants.get(simpleName);
            if (initializer == null || initializer.contains("(")) {
                return false;
            }
            return isConstantExpression(initializer, constants, visiting);
        } finally {
            visiting.remove(simpleName);
        }
    }

    /** Splits a {@code +} concatenation, ignoring operators inside string or char literals. */
    private static List<String> splitConcatenation(String expression) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && i + 1 < expression.length()) {
                    current.append(expression.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
            } else if (c == '+') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static boolean isStringLiteral(String token) {
        return token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"");
    }

    private static boolean isScreamingCase(String name) {
        return !name.isEmpty()
                && IDENTIFIER.matcher(name).matches()
                && name.equals(name.toUpperCase(Locale.ROOT))
                && !name.equals(name.toLowerCase(Locale.ROOT));
    }

    /** True when the expression, or a local it is built from, reads request-controlled input. */
    private static boolean containsRequestInput(String expression, Map<String, String> initializers,
                                                int depth) {
        if (expression == null || expression.isBlank() || depth > MAX_TAINT_DEPTH) {
            return false;
        }
        String lower = expression.toLowerCase(Locale.ROOT);
        for (String marker : REQUEST_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        Matcher matcher = IDENTIFIER.matcher(expression);
        while (matcher.find()) {
            String initializer = initializers.get(matcher.group());
            if (initializer != null && containsRequestInput(initializer, initializers, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private Violation violation(RuleContext context, Path file, int index,
                                String subtype, String message) {
        return new Violation(
                RuleId.AEM_SEC_002.id(),
                RuleId.AEM_SEC_002.defaultLevel(),
                subtype,
                message,
                context.projectRoot().relativize(file.toAbsolutePath().normalize())
                        .toString().replace('\\', '/'),
                new TextRegion(index + 1, 1, index + 1, 2),
                null,
                "Validate the target against an allowlist and never pass request parameters directly"
                        + " into redirects or outbound HTTP clients.",
                List.of());
    }
}
