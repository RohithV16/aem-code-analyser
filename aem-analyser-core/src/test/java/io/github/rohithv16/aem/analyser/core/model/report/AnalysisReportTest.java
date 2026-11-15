package io.github.rohithv16.aem.analyser.core.model.report;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisReportTest {

    @Test
    void legacyConstructorsProduceEmptyDiagnostics() {
        AnalysisReport fourArg = new AnalysisReport(Path.of("/mock"), Map.of(), List.of(), 1);
        AnalysisReport sixArg = new AnalysisReport(Path.of("/mock"), Map.of(), List.of(), 1,
                CoverageReport.notFound(), new CodeMetrics(0, 0, 0, 0, 0, 0.0));

        assertTrue(fourArg.diagnostics().isEmpty());
        assertTrue(sixArg.diagnostics().isEmpty());
    }

    @Test
    void withViolationsPreservesDiagnostics() {
        AnalysisDiagnostic diagnostic = AnalysisDiagnostic.of(
                DiagnosticKind.JAVA_PARSE_ERROR, Path.of("/mock/Broken.java"), "syntax error");
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(), List.of(), 1,
                CoverageReport.notFound(), new CodeMetrics(0, 0, 0, 0, 0, 0.0), List.of(diagnostic));

        AnalysisReport filtered = report.withViolations(List.of());

        assertEquals(1, filtered.diagnostics().size());
        assertEquals(diagnostic, filtered.diagnostics().get(0));
    }
}
