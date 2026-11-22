package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.CodeMetrics;
import io.github.rohithv16.aem.analyser.core.model.report.CoverageReport;
import io.github.rohithv16.aem.analyser.core.model.report.GateVerdict;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleReportFormatterTest {

    @Test
    void testViolationsAppearBeforeSummaryAndOrderedBySeverity() throws Exception {
        Violation infoViol = new Violation(
                "AEM-DEAD-003B",
                Severity.INFO,
                null,
                "Unused model",
                "core/Model.java",
                new TextRegion(10, 1, 10, 10),
                null,
                null,
                List.of()
        );

        Violation errorViol = new Violation(
                "AEM-DEAD-001",
                Severity.ERROR,
                null,
                "Orphaned config",
                "ui.config/config.cfg.json",
                new TextRegion(1, 1, 1, 10),
                null,
                null,
                List.of()
        );

        Violation warnViol = new Violation(
                "AEM-DUP-010",
                Severity.WARNING,
                null,
                "Duplicate code",
                "core/Service.java",
                new TextRegion(20, 1, 20, 10),
                null,
                null,
                List.of()
        );

        // Put INFO first, then WARN, then ERROR in the input report
        List<CodeMetrics.ClassDuplication> dupClasses = List.of(
                new CodeMetrics.ClassDuplication("Class1", "core/Class1.java", 10, 100, 10.0),
                new CodeMetrics.ClassDuplication("Class2", "core/Class2.java", 20, 100, 20.0),
                new CodeMetrics.ClassDuplication("Class3", "core/Class3.java", 30, 100, 30.0),
                new CodeMetrics.ClassDuplication("Class4", "core/Class4.java", 40, 100, 40.0),
                new CodeMetrics.ClassDuplication("Class5", "core/Class5.java", 50, 100, 50.0),
                new CodeMetrics.ClassDuplication("Class6", "core/Class6.java", 60, 100, 60.0),
                new CodeMetrics.ClassDuplication("Class7", "core/Class7.java", 70, 100, 70.0)
        );

        CodeMetrics metrics = new CodeMetrics(10, 1000, 210, 7, 7, 21.0, dupClasses);
        AnalysisReport report = new AnalysisReport(
                Path.of("/dummy/root"),
                Map.of("components", 5),
                List.of(infoViol, warnViol, errorViol),
                1500,
                CoverageReport.notFound(),
                metrics
        );

        ConsoleReportFormatter formatter = new ConsoleReportFormatter(false);
        StringWriter sw = new StringWriter();
        formatter.export(report, sw);
        String out = sw.toString();

        // 1. Errors should appear before Warnings, and Warnings before Infos
        int errorIdx = out.indexOf("[ERROR] AEM-DEAD-001");
        int warnIdx = out.indexOf("[WARNING] AEM-DUP-010");
        int infoIdx = out.indexOf("[INFO] AEM-DEAD-003B");
        int summaryIdx = out.indexOf("SCAN SUMMARY & METRICS");

        assertTrue(errorIdx != -1, "Error violation should be present");
        assertTrue(warnIdx != -1, "Warning violation should be present");
        assertTrue(infoIdx != -1, "Info violation should be present");
        assertTrue(summaryIdx != -1, "Summary section should be present");

        assertTrue(errorIdx < warnIdx, "ERROR must appear before WARNING");
        assertTrue(warnIdx < infoIdx, "WARNING must appear before INFO");
        assertTrue(infoIdx < summaryIdx, "All violations must appear BEFORE the scan summary");

        // 2. All 7 duplicate classes should be displayed without truncation
        for (int i = 1; i <= 7; i++) {
            assertTrue(out.contains("Class" + i + ":"), "Duplicate class Class" + i + " must be present in output");
        }
    }

    @Test
    void diagnosticsSummaryIsPrintedWhenFilesWereSkipped() throws Exception {
        AnalysisReport report = new AnalysisReport(
                Path.of("/dummy/root"),
                Map.of("modules", 2),
                List.of(),
                10,
                CoverageReport.notFound(),
                new CodeMetrics(0, 0, 0, 0, 0, 0.0),
                List.of(
                        io.github.rohithv16.aem.analyser.core.model.report.AnalysisDiagnostic.of(
                                io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.JAVA_PARSE_ERROR,
                                Path.of("/dummy/Broken.java"), "syntax error"),
                        io.github.rohithv16.aem.analyser.core.model.report.AnalysisDiagnostic.of(
                                io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.CONFIG_PARSE_ERROR,
                                Path.of("/dummy/config.cfg.json"), "invalid JSON")));

        StringWriter writer = new StringWriter();
        new ConsoleReportFormatter(false).export(report, writer);

        assertTrue(writer.toString().contains("Diagnostics: 2 file(s) skipped or degraded"),
                writer.toString());
    }

    /**
     * The verdict line is the one line CI pastes into a PR: it must describe the effective gate.
     * A report-only run with findings is {@code GATE NOT ENFORCED}, never {@code GATE FAILED}.
     */
    @Test
    void reportOnlyVerdictIsRenderedAsNotEnforced() throws Exception {
        Violation error = new Violation("AEM-DEAD-001", Severity.ERROR, null, "Orphaned config",
                "ui.config/config.cfg.json", new TextRegion(1, 1, 1, 10), null, null, List.of());
        AnalysisReport report = new AnalysisReport(
                Path.of("/dummy/root"), Map.of(), List.of(error), 10)
                .withGateVerdict(GateVerdict.notEnforced(
                        "1 error-severity finding, report-only run (--fail-on NONE)."));

        StringWriter writer = new StringWriter();
        new ConsoleReportFormatter(false).export(report, writer);
        String out = writer.toString();

        assertTrue(out.contains("GATE NOT ENFORCED: 1 error-severity finding, report-only run"),
                out);
        assertFalse(out.contains("GATE FAILED"), out);
    }

    @Test
    void thresholdFailureIsNamedInTheRenderedVerdict() throws Exception {
        AnalysisReport report = new AnalysisReport(
                Path.of("/dummy/root"), Map.of(), List.of(), 10)
                .withGateVerdict(GateVerdict.failed(
                        "duplication 6.2% exceeds --max-duplication 3.0%."));

        StringWriter writer = new StringWriter();
        new ConsoleReportFormatter(false).export(report, writer);

        assertTrue(writer.toString().contains(
                "GATE FAILED: duplication 6.2% exceeds --max-duplication 3.0%."), writer.toString());
    }

    /**
     * The answer has to be on screen before the findings: a 278-line log whose verdict is line 277
     * is read by scrolling to the bottom, which is exactly what this report must not require.
     */
    @Test
    void verdictCountsHotspotsAndReportPathsPrecedeTheFirstFinding() throws Exception {
        AnalysisReport report = new AnalysisReport(
                Path.of("/dummy/root"), Map.of("modules", 4),
                List.of(error(), warning(), info()), 6400);

        StringWriter writer = new StringWriter();
        new ConsoleReportFormatter(false, ConsoleGroupBy.NONE,
                List.of("target/aem-analyser/aem-analyser.sarif", "target/aem-analyser/aem-analyser-report.html"),
                false).export(report, writer);
        String out = writer.toString();

        String head = out.lines().limit(12).reduce("", (a, b) -> a + b + "\n");
        assertTrue(head.contains("AEM CODE ANALYSER \u2014 GATE FAILED"), head);
        assertTrue(head.contains("Total Violations: 3 (1 Error, 1 Warning, 1 Info)"), head);
        assertTrue(head.contains("Hotspots: "), head);
        assertTrue(head.contains("ui.config/config.cfg.json (1)"), head);
        assertTrue(head.contains("Reports: target/aem-analyser/aem-analyser.sarif"), head);
        assertTrue(head.contains("6.4 s"), head);
        assertTrue(head.contains("4 modules"), head);

        assertTrue(out.indexOf("GATE FAILED") < out.indexOf("[ERROR] AEM-DEAD-001"),
                "the verdict must be printed before the first finding:\n" + out);
        assertTrue(out.indexOf("Total Violations: 3") < out.indexOf("[ERROR] AEM-DEAD-001"), out);
    }

    /** --group-by file: one header per file with the count, one line per finding. */
    @Test
    void groupByFilePrintsOneHeaderPerFileWithCounts() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/dummy/root"), Map.of(),
                List.of(violation("AEM-DEAD-001", Severity.ERROR, "core/A.java", 1),
                        violation("AEM-DUP-010", Severity.WARNING, "core/A.java", 2),
                        violation("AEM-DEAD-003B", Severity.INFO, "core/A.java", 3),
                        violation("AEM-DEAD-001", Severity.ERROR, "core/B.java", 4),
                        violation("AEM-CLOUD-001", Severity.ERROR, "core/B.java", 5)),
                10);

        StringWriter writer = new StringWriter();
        new ConsoleReportFormatter(false, ConsoleGroupBy.FILE, List.of(), false).export(report, writer);
        String out = writer.toString();

        assertTrue(out.contains("FINDINGS (5) \u2014 grouped by file"), out);
        assertTrue(out.contains("core/A.java (3)"), out);
        assertTrue(out.contains("core/B.java (2)"), out);
        assertTrue(out.indexOf("core/A.java (3)") < out.indexOf("core/B.java (2)"),
                "the file with the most findings comes first:\n" + out);
        assertFalse(out.contains("  File: "), "grouped findings are one line each, not six-line blocks");
    }

    /** --group-by rule: the rule is stated once per group, the location on every finding. */
    @Test
    void groupByRulePrintsOneHeaderPerRuleWithCounts() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/dummy/root"), Map.of(),
                List.of(violation("AEM-DEAD-001", Severity.ERROR, "core/A.java", 1),
                        violation("AEM-DEAD-001", Severity.ERROR, "core/B.java", 2),
                        violation("AEM-DUP-010", Severity.WARNING, "core/A.java", 3)),
                10);

        StringWriter writer = new StringWriter();
        new ConsoleReportFormatter(false, ConsoleGroupBy.RULE, List.of(), false).export(report, writer);
        String out = writer.toString();

        assertTrue(out.contains("FINDINGS (3) \u2014 grouped by rule"), out);
        assertTrue(out.contains("AEM-DEAD-001 (2)"), out);
        assertTrue(out.contains("core/B.java:2"), out);
    }

    /** A reproducible run (fixed report clock) omits the wall-clock duration from the triage line. */
    @Test
    void reproducibleRunOmitsTheDuration() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/dummy/root"), Map.of(),
                List.of(error()), 6400);

        StringWriter writer = new StringWriter();
        new ConsoleReportFormatter(false, ConsoleGroupBy.NONE, List.of(), true).export(report, writer);
        String out = writer.toString();

        assertTrue(out.contains("Total Violations: 1 (1 Error, 0 Warnings, 0 Infos)"), out);
        assertFalse(out.contains("6.4 s"), out);
    }

    private static Violation violation(String ruleId, Severity severity, String path, int line) {
        return new Violation(ruleId, severity, null, "message at " + path + ":" + line, path,
                new TextRegion(line, 1, line, 10), null, null, List.of());
    }

    private static Violation error() {
        return new Violation("AEM-DEAD-001", Severity.ERROR, null, "Orphaned config",
                "ui.config/config.cfg.json", new TextRegion(1, 1, 1, 10), null, null, List.of());
    }

    private static Violation warning() {
        return new Violation("AEM-DUP-010", Severity.WARNING, null, "Duplicate code",
                "core/Service.java", new TextRegion(20, 1, 20, 10), null, null, List.of());
    }

    private static Violation info() {
        return new Violation("AEM-DEAD-003B", Severity.INFO, null, "Unused model",
                "core/Model.java", new TextRegion(10, 1, 10, 10), null, null, List.of());
    }
}

