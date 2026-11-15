package io.github.rohithv16.aem.analyser.core.baseline;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.util.List;

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
        List<Violation> filtered = report.violations().stream()
                .filter(violation -> !baseline.contains(Fingerprint.of(violation)))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        if (keepSuppressed) {
            for (Violation violation : report.violations()) {
                if (baseline.contains(Fingerprint.of(violation))) {
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
        }
        return report.withViolations(filtered);
    }

    public static int suppressedCount(AnalysisReport before, AnalysisReport after) {
        return Math.max(0, before.violations().size() - after.violations().size());
    }
}
