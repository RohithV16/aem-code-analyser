package io.github.rohithv16.aem.analyser.core.model.report;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The aggregate result of one analysis execution.
 *
 * @param projectRoot     absolute analysed project root
 * @param scanStatistics  counts of scanned artifacts by kind
 * @param violations      deterministic, rule-ordered violation list
 * @param durationMillis  total wall-clock analysis duration
 */
public record AnalysisReport(Path projectRoot,
                             Map<String, Integer> scanStatistics,
                             List<Violation> violations,
                             long durationMillis,
                             CoverageReport coverageReport,
                             CodeMetrics codeMetrics) {

    public AnalysisReport(Path projectRoot,
                          Map<String, Integer> scanStatistics,
                          List<Violation> violations,
                          long durationMillis) {
        this(projectRoot, scanStatistics, violations, durationMillis,
                CoverageReport.notFound(), new CodeMetrics(0, 0, 0, 0, 0, 0.0));
    }

    public AnalysisReport {
        projectRoot = projectRoot.toAbsolutePath().normalize();
        scanStatistics = Map.copyOf(scanStatistics == null ? Map.of() : scanStatistics);
        violations = List.copyOf(violations == null ? List.of() : violations);
        if (coverageReport == null) {
            coverageReport = CoverageReport.notFound();
        }
        if (codeMetrics == null) {
            codeMetrics = new CodeMetrics(0, 0, 0, 0, 0, 0.0);
        }
    }

    public long countBySeverity(Severity severity) {
        return violations.stream().filter(v -> v.severity() == severity).count();
    }
}
