package io.github.rohithv16.aem.analyser.core.model.report;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The aggregate result of one analysis execution.
 *
 * @param projectRoot     absolute analysed project root
 * @param scanStatistics  counts of scanned artifacts by kind
 * @param violations      deterministic, rule-ordered violation list
 * @param durationMillis  total wall-clock analysis duration
 * @param gateVerdict     outcome of the effective quality gate; every renderer prints this instead
 *                        of deriving a verdict from the error count
 * @param diagnostics     non-fatal parse/read problems encountered during the run
 */
public record AnalysisReport(Path projectRoot,
                             Map<String, Integer> scanStatistics,
                             List<Violation> violations,
                             long durationMillis,
                             CoverageReport coverageReport,
                             CodeMetrics codeMetrics,
                             List<AnalysisDiagnostic> diagnostics,
                             GateVerdict gateVerdict) {

    public AnalysisReport(Path projectRoot,
                          Map<String, Integer> scanStatistics,
                          List<Violation> violations,
                          long durationMillis) {
        this(projectRoot, scanStatistics, violations, durationMillis,
                CoverageReport.notFound(), new CodeMetrics(0, 0, 0, 0, 0, 0.0), List.of());
    }

    public AnalysisReport(Path projectRoot,
                          Map<String, Integer> scanStatistics,
                          List<Violation> violations,
                          long durationMillis,
                          CoverageReport coverageReport,
                          CodeMetrics codeMetrics) {
        this(projectRoot, scanStatistics, violations, durationMillis, coverageReport, codeMetrics, List.of());
    }

    public AnalysisReport(Path projectRoot,
                          Map<String, Integer> scanStatistics,
                          List<Violation> violations,
                          long durationMillis,
                          CoverageReport coverageReport,
                          CodeMetrics codeMetrics,
                          List<AnalysisDiagnostic> diagnostics) {
        this(projectRoot, scanStatistics, violations, durationMillis, coverageReport, codeMetrics,
                diagnostics, null);
    }

    public AnalysisReport {
        projectRoot = projectRoot.toAbsolutePath().normalize();
        scanStatistics = Collections.unmodifiableMap(new TreeMap<>(
                scanStatistics == null ? Map.of() : scanStatistics));
        violations = List.copyOf(violations == null ? List.of() : violations);
        if (coverageReport == null) {
            coverageReport = CoverageReport.notFound();
        }
        if (codeMetrics == null) {
            codeMetrics = new CodeMetrics(0, 0, 0, 0, 0, 0.0);
        }
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
        if (gateVerdict == null) {
            gateVerdict = GateVerdict.forCounts(
                    countBySeverity(violations, Severity.ERROR),
                    countBySeverity(violations, Severity.WARNING),
                    countBySeverity(violations, Severity.INFO),
                    !violations.isEmpty());
        }
    }

    public long countBySeverity(Severity severity) {
        return countBySeverity(violations, severity);
    }

    private static long countBySeverity(List<Violation> violations, Severity severity) {
        return violations.stream().filter(v -> v.severity() == severity).count();
    }

    public AnalysisReport withViolations(List<Violation> newViolations) {
        return new AnalysisReport(projectRoot, scanStatistics, newViolations, durationMillis,
                coverageReport, codeMetrics, diagnostics, gateVerdict);
    }

    public AnalysisReport withDiagnostics(List<AnalysisDiagnostic> newDiagnostics) {
        return new AnalysisReport(projectRoot, scanStatistics, violations, durationMillis,
                coverageReport, codeMetrics, newDiagnostics, gateVerdict);
    }

    /** Returns a copy carrying the verdict of the effective gate for this run. */
    public AnalysisReport withGateVerdict(GateVerdict verdict) {
        return new AnalysisReport(projectRoot, scanStatistics, violations, durationMillis,
                coverageReport, codeMetrics, diagnostics, verdict);
    }
}
