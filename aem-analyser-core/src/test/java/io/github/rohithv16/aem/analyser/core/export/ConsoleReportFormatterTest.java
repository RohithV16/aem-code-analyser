package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.CodeMetrics;
import io.github.rohithv16.aem.analyser.core.model.report.CoverageReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
}
