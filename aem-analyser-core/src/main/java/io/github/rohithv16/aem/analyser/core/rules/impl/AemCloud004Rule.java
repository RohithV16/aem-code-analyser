package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AEM-CLOUD-004: imports or uses of APIs deprecated/removed in AEM as a Cloud Service.
 *
 * <p>A compilation unit can use a deprecated type without importing it - {@code
 * com.day.cq.wcm.api.WCMMode.EDIT} fully qualified inside a method body - so matching {@code import}
 * lines alone let such a project pass the gate. The rule scans every source line for a qualified
 * reference as well, and reports a type once per compilation unit when it is imported (the import
 * line is the better fix site) or once per line when it is only referenced fully qualified.
 */
public final class AemCloud004Rule implements RuleEvaluator {

    /** Deprecated types, in report order. */
    private static final List<String> DEPRECATED_TYPES = List.of(
            "org.apache.commons.lang.StringUtils",
            "org.apache.commons.lang.ArrayUtils",
            "org.apache.commons.lang.StringEscapeUtils",
            "com.day.cq.wcm.api.WCMMode",
            "com.day.cq.wcm.api.designer.Designer",
            "com.day.cq.wcm.api.designer.Style");

    /**
     * One matcher per deprecated type: the FQN must not be glued to a preceding identifier character
     * or another dotted segment, and must not continue as a longer simple name. A trailing {@code .}
     * is allowed because {@code ...WCMMode.EDIT} is a use of the type.
     */
    private static final List<TypeMatcher> MATCHERS = DEPRECATED_TYPES.stream()
            .map(fqn -> new TypeMatcher(fqn, Pattern.compile(
                    "(?<![\\w$.])" + Pattern.quote(fqn) + "(?![\\w$])")))
            .toList();

    private record TypeMatcher(String fqn, Pattern pattern) {
    }

    @Override
    public String getRuleId() {
        return RuleId.AEM_CLOUD_004.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (java.nio.file.Path file : context.javaTypeFiles()) {
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                violations.addAll(evaluateFile(context, file, Files.readAllLines(file)));
            } catch (IOException ignored) {
                // unreadable file: diagnostics are handled by the parser
            }
        }
        return violations;
    }

    private List<Violation> evaluateFile(RuleContext context, java.nio.file.Path file, List<String> lines) {
        List<Violation> violations = new ArrayList<>();
        Set<String> imported = new HashSet<>();

        // Pass 1: imports. A type imported here is reported at the import, not at each use.
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (!line.startsWith("import ")) {
                continue;
            }
            for (TypeMatcher matcher : MATCHERS) {
                if (line.contains(matcher.fqn())) {
                    imported.add(matcher.fqn());
                    violations.add(violation(context, file, i + 1, line.length(),
                            "Import '" + matcher.fqn() + "' uses an API deprecated or removed in"
                                    + " AEM as a Cloud Service.",
                            matcher.fqn()));
                }
            }
        }

        // Pass 2: fully-qualified uses with no import, the case the prefix filter used to miss.
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String stripped = line.strip();
            if (stripped.startsWith("import ") || isComment(stripped)) {
                continue;
            }
            for (TypeMatcher matcher : MATCHERS) {
                if (imported.contains(matcher.fqn()) || !line.contains(matcher.fqn())) {
                    continue;
                }
                Matcher match = matcher.pattern().matcher(line);
                if (match.find()) {
                    violations.add(violation(context, file, i + 1, match.end(),
                            "Fully-qualified use of '" + matcher.fqn() + "' relies on an API"
                                    + " deprecated or removed in AEM as a Cloud Service.",
                            matcher.fqn()));
                }
            }
        }
        return violations;
    }

    /** Lines that cannot contain a type reference worth reporting. */
    private static boolean isComment(String stripped) {
        return stripped.startsWith("//") || stripped.startsWith("/*")
                || stripped.startsWith("*") || stripped.startsWith("*/");
    }

    private Violation violation(RuleContext context, java.nio.file.Path file, int lineNumber, int endColumn,
                                String message, String fqn) {
        return new Violation(
                RuleId.AEM_CLOUD_004.id(),
                RuleId.AEM_CLOUD_004.defaultLevel(),
                "DEPRECATED_AEM_API",
                message,
                relativePath(context, file),
                new TextRegion(lineNumber, 1, lineNumber, Math.max(2, endColumn)),
                null,
                "Replace '" + fqn + "' with the supported API for AEM as a Cloud Service.",
                List.of());
    }

    private String relativePath(RuleContext context, java.nio.file.Path path) {
        return context.relativePath(path);
    }
}
