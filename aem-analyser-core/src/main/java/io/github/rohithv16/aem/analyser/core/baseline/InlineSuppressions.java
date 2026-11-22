package io.github.rohithv16.aem.analyser.core.baseline;

import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Inline suppression directives understood in Java, HTL and XML comments:
 *
 * <pre>
 *   // aem-analyser-disable-next-line AEM-DEAD-001
 *   &lt;!-- aem-analyser-disable-next-line AEM-DEAD-002A --&gt;
 *   # aem-analyser-disable-file AEM-DUP-010
 * </pre>
 *
 * <p>A directive without rule ids suppresses every rule at that position. Matching is per rule id;
 * a suppression never hides a different rule by accident.
 */
public final class InlineSuppressions {

    private static final String NEXT_LINE = "aem-analyser-disable-next-line";
    private static final String FILE = "aem-analyser-disable-file";

    private InlineSuppressions() {
    }

    public static boolean isSuppressed(Path projectRoot, Violation violation) {
        return isSuppressed(projectRoot, violation, new java.util.HashMap<>());
    }

    /**
     * Filters a whole report with a per-file line cache so a large report does not re-read the same
     * source file for every violation.
     */
    public static List<Violation> filter(Path projectRoot, List<Violation> violations) {
        if (violations == null || violations.isEmpty()) {
            return violations;
        }
        return annotate(projectRoot, violations).stream()
                .filter(violation -> !violation.isAccepted())
                .toList();
    }

    /**
     * Returns the report's findings with inline-suppressed entries marked
     * {@link io.github.rohithv16.aem.analyser.core.model.report.AcceptanceOrigin#INLINE} instead of
     * removed. The caller splits accepted from actionable findings, so the gate counts stay identical
     * while exporters can still show the waiver (SARIF {@code suppressions}).
     */
    public static List<Violation> annotate(Path projectRoot, List<Violation> violations) {
        if (violations == null || violations.isEmpty()) {
            return violations;
        }
        java.util.Map<Path, List<String>> cache = new java.util.HashMap<>();
        List<Violation> annotated = new java.util.ArrayList<>(violations.size());
        for (Violation violation : violations) {
            // Never overwrite an acceptance recorded earlier (a baseline entry): the first, most
            // specific waiver wins and the justification stays truthful.
            if (violation.isAccepted() || !isSuppressed(projectRoot, violation, cache)) {
                annotated.add(violation);
            } else {
                annotated.add(violation.withAcceptance(
                        io.github.rohithv16.aem.analyser.core.model.report.AcceptanceOrigin.INLINE));
            }
        }
        return annotated;
    }

    private static boolean isSuppressed(Path projectRoot, Violation violation,
                                        java.util.Map<Path, List<String>> cache) {
        if (violation == null || violation.filePath() == null) {
            return false;
        }
        Path file = Path.of(violation.filePath());
        if (!file.isAbsolute() && projectRoot != null) {
            file = projectRoot.resolve(file);
        }
        if (!Files.isRegularFile(file)) {
            return false;
        }
        List<String> lines = cache.get(file);
        if (lines == null) {
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                lines = List.of();
            }
            cache.put(file, lines);
        }
        String ruleId = violation.ruleId();
        for (String line : lines) {
            if (directiveApplies(line, FILE, ruleId)) {
                return true;
            }
        }
        int lineNumber = violation.region() == null ? 0 : violation.region().startLine();
        // Only the line immediately PRECEDING the finding (1-based N-1 == 0-based N-2). The finding's
        // own line used to match too, so a directive documented on the affected line suppressed it.
        int index = lineNumber - 2;
        if (index >= 0 && index < lines.size() && directiveApplies(lines.get(index), NEXT_LINE, ruleId)) {
            return true;
        }
        return false;
    }

    private static boolean directiveApplies(String line, String directive, String ruleId) {
        int position = line.indexOf(directive);
        if (position < 0) {
            return false;
        }
        // A directive only counts inside a comment: occurrences inside string literals or plain
        // prose used to suppress findings silently.
        if (!isCommentPrefix(line.substring(0, position))) {
            return false;
        }
        String remainder = line.substring(position + directive.length()).trim();
        if (remainder.startsWith("-->")) {
            remainder = remainder.substring(3).trim();
        }
        if (remainder.isEmpty()) {
            return true; // no rule ids: applies to every rule
        }
        // ':' must survive tokenisation so SonarJava ids such as java:S1166 can be suppressed.
        for (String token : remainder.split("[^A-Za-z0-9_*.:-]+")) {
            if (token.equals("*") || token.equalsIgnoreCase(ruleId)) {
                return true;
            }
            // A directive written before an id was retired still means the rule that replaced it.
            if (io.github.rohithv16.aem.analyser.core.rules.RuleCatalog.isAlias(token)
                    && io.github.rohithv16.aem.analyser.core.rules.RuleCatalog.resolve(token)
                            .equalsIgnoreCase(ruleId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCommentPrefix(String prefix) {
        // An odd number of unescaped double quotes before the directive means it sits inside a string
        // literal, not a comment ("// aem-analyser-..." in a message must not suppress anything).
        int quotes = 0;
        for (int i = 0; i < prefix.length(); i++) {
            if (prefix.charAt(i) == '"' && (i == 0 || prefix.charAt(i - 1) != '\\')) {
                quotes++;
            }
        }
        if (quotes % 2 == 1) {
            return false;
        }
        String trimmed = prefix.strip();
        return trimmed.isEmpty()
                || trimmed.endsWith("//")
                || trimmed.endsWith("/*")
                || trimmed.endsWith("*")
                || trimmed.endsWith("<!--")
                || trimmed.endsWith("#")
                || trimmed.contains("//")
                || trimmed.contains("<!--")
                || trimmed.contains("/*");
    }
}
