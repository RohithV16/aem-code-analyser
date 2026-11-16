package io.github.rohithv16.aem.analyser.core.baseline;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.util.List;
import java.util.Map;

/**
 * Removes findings already accepted in a baseline.
 */
public final class BaselineFilter {

    private BaselineFilter() {
    }

    public static AnalysisReport filter(AnalysisReport report, Baseline baseline) {
        return filter(report, baseline, false);
    }

    /**
     * @param keepSuppressed when true, accepted findings are retained at INFO with a
     *                       {@code SUPPRESSED_BY_BASELINE} subtype instead of being removed
     */
    public static AnalysisReport filter(AnalysisReport report, Baseline baseline, boolean keepSuppressed) {
        if (baseline == null || baseline.entries().isEmpty()) {
            return report;
        }
        // A baseline stores one entry per accepted occurrence, so identical fingerprints are a
        // multiset: N accepted copies may suppress N occurrences, never an unlimited number. Using
        // contains() let a single accepted finding grandfather every future duplicate forever.
        Map<String, Integer> remaining = new java.util.HashMap<>();
        for (BaselineEntry entry : baseline.entries()) {
            remaining.merge(entry.fingerprint(), 1, Integer::sum);
        }

        List<Violation> filtered = new java.util.ArrayList<>();
        List<Violation> suppressed = new java.util.ArrayList<>();
        for (Violation violation : report.violations()) {
            String fingerprint = Fingerprint.of(violation);
            int allowed = remaining.getOrDefault(fingerprint, 0);
            if (allowed > 0) {
                remaining.put(fingerprint, allowed - 1);
                suppressed.add(violation);
            } else {
                filtered.add(violation);
            }
        }
        if (keepSuppressed) {
            for (Violation violation : suppressed) {
                filtered.add(new Violation(
                        violation.ruleId(),
                        io.github.rohithv16.aem.analyser.core.model.report.Severity.INFO,
                        "SUPPRESSED_BY_BASELINE",
                        "[baseline] " + violation.message(),
                        violation.filePath(),
                        violation.region(),
                        violation.targetPid(),
                        violation.remediation(),
                        violation.relatedLocations()));
            }
        }
        return report.withViolations(filtered);
    }

    public static int suppressedCount(AnalysisReport before, AnalysisReport after) {
        return Math.max(0, before.violations().size() - after.violations().size());
    }
}
