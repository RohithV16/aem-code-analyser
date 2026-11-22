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
 * @param acceptedViolations findings the project already accepted (baseline entry or inline
 *                        {@code aem-analyser-disable-*} directive). They are deliberately NOT part
 *                        of {@link #violations()}: the gate, the console summary and the exit code
 *                        count only actionable findings. Exporters that can express a suppressed
 *                        result (SARIF) emit them so the report shows "N exist, N accepted"
 *                        instead of pretending the code is clean.
 * @param phaseMillis     wall-clock millis per phase, in run order (empty when unmeasured)
 * @param cacheSummary    parse-cache hit/miss line, or {@code null} when caching was off
 */
public record AnalysisReport(Path projectRoot,
                             Map<String, Integer> scanStatistics,
                             List<Violation> violations,
                             long durationMillis,
                             CoverageReport coverageReport,
                             CodeMetrics codeMetrics,
                             List<AnalysisDiagnostic> diagnostics,
                             GateVerdict gateVerdict,
                             List<Violation> acceptedViolations,
                             Map<String, Long> phaseMillis,
                             String cacheSummary) {

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

    public AnalysisReport(Path projectRoot,
                          Map<String, Integer> scanStatistics,
                          List<Violation> violations,
                          long durationMillis,
                          CoverageReport coverageReport,
                          CodeMetrics codeMetrics,
                          List<AnalysisDiagnostic> diagnostics,
                          GateVerdict gateVerdict) {
        this(projectRoot, scanStatistics, violations, durationMillis, coverageReport, codeMetrics,
                diagnostics, gateVerdict, List.of(), Map.of(), null);
    }

    public AnalysisReport {
        projectRoot = projectRoot.toAbsolutePath().normalize();
        phaseMillis = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(
                phaseMillis == null ? Map.of() : phaseMillis));
        cacheSummary = cacheSummary == null || cacheSummary.isBlank() ? null : cacheSummary;
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
        acceptedViolations = List.copyOf(acceptedViolations == null ? List.of() : acceptedViolations);
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
                coverageReport, codeMetrics, diagnostics, gateVerdict, acceptedViolations,
                phaseMillis, cacheSummary);
    }

    /** Returns a copy carrying a different accepted-findings list; the gate-visible list is unchanged. */
    public AnalysisReport withAcceptedViolations(List<Violation> newAcceptedViolations) {
        return new AnalysisReport(projectRoot, scanStatistics, violations, durationMillis,
                coverageReport, codeMetrics, diagnostics, gateVerdict, newAcceptedViolations,
                phaseMillis, cacheSummary);
    }

    public AnalysisReport withDiagnostics(List<AnalysisDiagnostic> newDiagnostics) {
        return new AnalysisReport(projectRoot, scanStatistics, violations, durationMillis,
                coverageReport, codeMetrics, newDiagnostics, gateVerdict, acceptedViolations,
                phaseMillis, cacheSummary);
    }

    /** Returns a copy carrying the verdict of the effective gate for this run. */
    public AnalysisReport withGateVerdict(GateVerdict verdict) {
        return new AnalysisReport(projectRoot, scanStatistics, violations, durationMillis,
                coverageReport, codeMetrics, diagnostics, verdict, acceptedViolations,
                phaseMillis, cacheSummary);
    }

    /** Returns a copy carrying per-phase wall-clock millis, in run order. */
    public AnalysisReport withPhaseMillis(Map<String, Long> newPhaseMillis) {
        return new AnalysisReport(projectRoot, scanStatistics, violations, durationMillis,
                coverageReport, codeMetrics, diagnostics, gateVerdict, acceptedViolations,
                newPhaseMillis, cacheSummary);
    }

    /** Returns a copy carrying the parse-cache hit/miss line, or {@code null} when caching is off. */
    public AnalysisReport withCacheSummary(String newCacheSummary) {
        return new AnalysisReport(projectRoot, scanStatistics, violations, durationMillis,
                coverageReport, codeMetrics, diagnostics, gateVerdict, acceptedViolations,
                phaseMillis, newCacheSummary);
    }
}
