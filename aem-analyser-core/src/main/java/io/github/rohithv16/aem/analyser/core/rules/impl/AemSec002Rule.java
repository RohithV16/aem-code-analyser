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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AEM-SEC-002: open redirect / SSRF when request-controlled data reaches a redirect or outbound URL.
 *
 * <p>Scans every Java type (components, servlets and Sling Models included) and treats a redirect
 * target as safe when it is a string literal, a compile-time constant, or a concatenation of those -
 * only request-derived or unknown values are reported.
 */
public final class AemSec002Rule implements RuleEvaluator {

    private static final Pattern STRING_CONSTANT = Pattern.compile(
            "(?:static\\s+final|final)\\s+String\\s+([A-Za-z_$][\\w$]*)\\s*=");

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
                Set<String> constants = collectStringConstants(lines);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String lower = line.toLowerCase(Locale.ROOT);
                    int redirect = lower.indexOf("sendredirect(");
                    if (redirect >= 0) {
                        String argument = argumentOf(line, redirect + "sendredirect(".length());
                        if (!isCompileTimeConstant(argument, constants)) {
                            violations.add(violation(context, file, i, "OPEN_REDIRECT",
                                    "Redirect target is not a constant and may be user-controlled."));
                        }
                        continue;
                    }
                    if (lower.contains("new url(")
                            && (lower.contains("getparameter") || lower.contains("request."))) {
                        violations.add(violation(context, file, i, "SSRF",
                                "Outbound URL is built from request-controlled input."));
                    }
                }
            } catch (IOException ignored) {
                // unreadable file: diagnostics handled by the parser
            }
        }
        return violations;
    }

    /** Names of {@code static final String} / {@code final String} declarations in the file. */
    private static Set<String> collectStringConstants(List<String> lines) {
        Set<String> constants = new HashSet<>();
        for (String line : lines) {
            Matcher matcher = STRING_CONSTANT.matcher(line);
            while (matcher.find()) {
                constants.add(matcher.group(1));
            }
        }
        return constants;
    }

    /** Extracts the first argument of the call starting at {@code from}, honouring nested parens. */
    private static String argumentOf(String line, int from) {
        int depth = 1;
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '(') {
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
    private static boolean isCompileTimeConstant(String argument, Set<String> constants) {
        if (argument == null || argument.isBlank()) {
            return false;
        }
        String value = argument.strip();
        if (value.startsWith("\"")) {
            return true;
        }
        if (value.contains("(")) {
            return false;
        }
        for (String part : value.split("\\+")) {
            String token = part.strip();
            if (token.startsWith("\"")) {
                continue;
            }
            boolean qualified = token.indexOf('.') > 0;
            String simpleName = token.substring(token.lastIndexOf('.') + 1).strip();
            if (constants.contains(simpleName)) {
                continue;
            }
            // A qualified reference whose member is SCREAMING_CASE is a constant declared in another
            // type (Type.NAME). An UNQUALIFIED screaming-case token may just as well be a local
            // variable holding user input, so it is not treated as constant.
            boolean qualifiedConstant = qualified
                    && simpleName.equals(simpleName.toUpperCase(Locale.ROOT))
                    && !simpleName.equals(simpleName.toLowerCase(Locale.ROOT));
            if (!qualifiedConstant) {
                return false;
            }
        }
        return true;
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
