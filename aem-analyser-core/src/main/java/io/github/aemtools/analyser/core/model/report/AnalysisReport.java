package io.github.aemtools.analyser.core.model.report;

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
                             long durationMillis) {

    public AnalysisReport {
        projectRoot = projectRoot.toAbsolutePath().normalize();
        scanStatistics = Map.copyOf(scanStatistics == null ? Map.of() : scanStatistics);
        violations = List.copyOf(violations == null ? List.of() : violations);
    }

    public long countBySeverity(Severity severity) {
        return violations.stream().filter(v -> v.severity() == severity).count();
    }
}
