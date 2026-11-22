package io.github.rohithv16.aem.analyser.maven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Issue #47: the set difference between a previous run and this one.
 *
 * <p>Matching is a <em>multiset</em> match, not a set membership test: two findings of the same rule
 * in the same file with the same message are two findings, so deleting one of them must report one
 * resolved finding and not zero. Fingerprints (line-insensitive by design) are the primary
 * identity; the content key is the fallback for a report that carries no fingerprint.
 */
final class ReportDiff {

    /** The delta for one rule. */
    record RuleDelta(String ruleId, int added, int resolved, int unchanged) {
    }

    /** The whole delta: the findings that moved in each direction plus the per-rule summary. */
    record Result(List<FindingSet.Finding> added,
                  List<FindingSet.Finding> resolved,
                  List<FindingSet.Finding> unchanged,
                  List<RuleDelta> byRule) {

        /** How many new findings reach the gate threshold (INFO is the widest threshold). */
        int addedAtOrAbove(Severity gate) {
            if (gate == null) {
                return 0;
            }
            return (int) added.stream()
                    .filter(finding -> finding.severity() != null
                            && finding.severity().ordinal() <= gate.ordinal())
                    .count();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ReportDiff() {
    }

    static Result compute(List<FindingSet.Finding> previous, List<FindingSet.Finding> current) {
        Map<String, Deque<FindingSet.Finding>> byFingerprint = new LinkedHashMap<>();
        Map<String, Deque<FindingSet.Finding>> byContent = new LinkedHashMap<>();
        for (FindingSet.Finding finding : previous == null ? List.<FindingSet.Finding>of() : previous) {
            if (finding.fingerprint() != null) {
                byFingerprint.computeIfAbsent(finding.fingerprint(), key -> new ArrayDeque<>()).add(finding);
            } else {
                byContent.computeIfAbsent(finding.contentKey(), key -> new ArrayDeque<>()).add(finding);
            }
        }

        List<FindingSet.Finding> added = new ArrayList<>();
        List<FindingSet.Finding> unchanged = new ArrayList<>();
        for (FindingSet.Finding finding : current == null ? List.<FindingSet.Finding>of() : current) {
            if (take(byFingerprint, finding.fingerprint()) || take(byContent, finding.contentKey())) {
                unchanged.add(finding);
            } else {
                added.add(finding);
            }
        }

        List<FindingSet.Finding> resolved = new ArrayList<>();
        for (Map.Entry<String, Deque<FindingSet.Finding>> entry : byFingerprint.entrySet()) {
            resolved.addAll(entry.getValue());
        }
        for (Map.Entry<String, Deque<FindingSet.Finding>> entry : byContent.entrySet()) {
            resolved.addAll(entry.getValue());
        }
        return new Result(List.copyOf(added), List.copyOf(resolved), List.copyOf(unchanged),
                byRule(added, resolved, unchanged));
    }

    private static boolean take(Map<String, Deque<FindingSet.Finding>> pool, String key) {
        if (key == null) {
            return false;
        }
        Deque<FindingSet.Finding> bucket = pool.get(key);
        if (bucket == null || bucket.isEmpty()) {
            return false;
        }
        bucket.removeFirst();
        return true;
    }

    private static List<RuleDelta> byRule(List<FindingSet.Finding> added,
                                          List<FindingSet.Finding> resolved,
                                          List<FindingSet.Finding> unchanged) {
        TreeSet<String> rules = new TreeSet<>();
        for (List<FindingSet.Finding> findings : List.of(added, resolved, unchanged)) {
            for (FindingSet.Finding finding : findings) {
                rules.add(finding.ruleId() == null ? "unknown" : finding.ruleId());
            }
        }
        List<RuleDelta> deltas = new ArrayList<>();
        for (String rule : rules) {
            deltas.add(new RuleDelta(rule,
                    count(added, rule), count(resolved, rule), count(unchanged, rule)));
        }
        return List.copyOf(deltas);
    }

    private static int count(List<FindingSet.Finding> findings, String rule) {
        int count = 0;
        for (FindingSet.Finding finding : findings) {
            if (rule.equals(finding.ruleId() == null ? "unknown" : finding.ruleId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Renders the {@code new / resolved / unchanged} table, one row per rule that moved or stayed,
     * plus a total. Deterministic: rules are sorted by id.
     */
    static List<String> renderTable(Result result) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT,
                "AEM Code Analyser diff: %d new, %d resolved, %d unchanged",
                result.added().size(), result.resolved().size(), result.unchanged().size()));
        if (result.byRule().isEmpty()) {
            lines.add("  no findings in either report.");
            return lines;
        }
        int width = "RULE".length();
        for (RuleDelta delta : result.byRule()) {
            width = Math.max(width, delta.ruleId().length());
        }
        lines.add(String.format(Locale.ROOT, "  %-" + width + "s  %5s  %8s  %9s",
                "RULE", "NEW", "RESOLVED", "UNCHANGED"));
        int added = 0;
        int resolved = 0;
        int unchanged = 0;
        for (RuleDelta delta : result.byRule()) {
            lines.add(String.format(Locale.ROOT, "  %-" + width + "s  %5d  %8d  %9d",
                    delta.ruleId(), delta.added(), delta.resolved(), delta.unchanged()));
            added += delta.added();
            resolved += delta.resolved();
            unchanged += delta.unchanged();
        }
        lines.add(String.format(Locale.ROOT, "  %-" + width + "s  %5d  %8d  %9d",
                "TOTAL", added, resolved, unchanged));
        return lines;
    }

    /** One line per new finding, so a failing gate names what to fix instead of only a count. */
    static List<String> renderFindings(String heading, List<FindingSet.Finding> findings) {
        List<String> lines = new ArrayList<>();
        if (findings.isEmpty()) {
            return lines;
        }
        lines.add(heading);
        for (FindingSet.Finding finding : findings) {
            String location = finding.path() == null || finding.path().isEmpty() ? "(no location)" : finding.path();
            lines.add("  - [" + finding.severity() + "] " + finding.ruleId() + " " + location + ": "
                    + finding.message());
        }
        return lines;
    }

    /** The {@code diff.json} document. Ordered maps and no timestamps: two identical runs diff clean. */
    static String toJson(Result result, String previous, String current) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1);
        document.put("previous", previous);
        document.put("current", current);
        document.put("new", result.added().size());
        document.put("resolved", result.resolved().size());
        document.put("unchanged", result.unchanged().size());
        List<Map<String, Object>> rules = new ArrayList<>();
        for (RuleDelta delta : result.byRule()) {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("ruleId", delta.ruleId());
            rule.put("new", delta.added());
            rule.put("resolved", delta.resolved());
            rule.put("unchanged", delta.unchanged());
            rules.add(rule);
        }
        document.put("byRule", rules);
        document.put("newFindings", findingsJson(result.added()));
        document.put("resolvedFindings", findingsJson(result.resolved()));
        try {
            return MAPPER.writeValueAsString(document) + System.lineSeparator();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise the diff document", e);
        }
    }

    private static List<Map<String, Object>> findingsJson(List<FindingSet.Finding> findings) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (FindingSet.Finding finding : findings) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ruleId", finding.ruleId());
            entry.put("severity", finding.severity() == null ? null : finding.severity().name());
            entry.put("path", finding.path());
            entry.put("message", finding.message());
            entries.add(entry);
        }
        return entries;
    }
}
