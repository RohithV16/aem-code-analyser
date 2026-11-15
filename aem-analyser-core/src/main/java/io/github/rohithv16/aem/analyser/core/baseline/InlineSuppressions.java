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
        java.util.Map<Path, List<String>> cache = new java.util.HashMap<>();
        return violations.stream()
                .filter(violation -> !isSuppressed(projectRoot, violation, cache))
                .toList();
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
        for (int index : new int[]{lineNumber - 2, lineNumber - 1}) {
            if (index >= 0 && index < lines.size()
                    && directiveApplies(lines.get(index), NEXT_LINE, ruleId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean directiveApplies(String line, String directive, String ruleId) {
        int position = line.indexOf(directive);
        if (position < 0) {
            return false;
        }
        String remainder = line.substring(position + directive.length()).trim();
        if (remainder.startsWith("-->")) {
            remainder = remainder.substring(3).trim();
        }
        if (remainder.isEmpty()) {
            return true; // no rule ids: applies to every rule
        }
        for (String token : remainder.split("[^A-Za-z0-9_*.-]+")) {
            if (token.equals("*") || token.equalsIgnoreCase(ruleId)) {
                return true;
            }
        }
        return false;
    }
}
