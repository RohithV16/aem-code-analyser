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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlReportExporterTest {

    @Test
    void testHtmlReportGeneration() throws Exception {
        Violation err = new Violation(
                "AEM-CLOUD-001",
                Severity.ERROR,
                "HARDCODED_CLOUD_SECRET",
                "Plaintext apiKey in config",
                "ui.config/com.example.Service.cfg.json",
                new TextRegion(5, 4, 5, 20),
                "com.example.Service",
                "Use $[secret:API_KEY]",
                List.of()
        );

        Violation warn = new Violation(
                "AEM-PERF-001",
                Severity.WARNING,
                "UNCLOSED_RESOURCE_RESOLVER",
                "Unclosed ResourceResolver",
                "core/MyService.java",
                new TextRegion(42, 10, 42, 30),
                null,
                "Wrap in try-with-resources",
                List.of()
        );

        CodeMetrics metrics = new CodeMetrics(
                10, 1500, 50, 10, 2, 3.3,
                List.of(new CodeMetrics.ClassDuplication("MyService", "core/MyService.java", 25, 200, 12.5))
        );

        CoverageReport cov = new CoverageReport(
                true, "jacoco.xml", 170, 200, 85.0, 30, 40, 75.0
        );

        AnalysisReport report = new AnalysisReport(
                Path.of("/workspace/project"),
                Map.of("components", 5, "configs", 2),
                List.of(err, warn),
                1250,
                cov,
                metrics
        );

        HtmlReportExporter exporter = new HtmlReportExporter();
        assertEquals(ReportFormat.HTML, exporter.getFormat());

        StringWriter writer = new StringWriter();
        exporter.export(report, writer);
        String html = writer.toString();

        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("AEM Code Analyser"));
        assertTrue(html.contains("AEM-CLOUD-001"));
        assertTrue(html.contains("Plaintext apiKey in config"));
        assertTrue(html.contains("AEM-PERF-001"));
        assertTrue(html.contains("Unclosed ResourceResolver"));
        assertTrue(html.contains("85.0%"));
        assertTrue(html.contains("MyService"));
        assertTrue(html.contains("searchInput"));
    }

    @Test
    void deterministicForAFixedClock() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/project"), Map.of("components", 1),
                List.of(violation("AEM-DEAD-001", "message", 1)), 10);
        java.time.Clock fixed = java.time.Clock.fixed(
                java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);
        HtmlReportExporter exporter = new HtmlReportExporter(fixed, 5000);

        StringWriter first = new StringWriter();
        StringWriter second = new StringWriter();
        exporter.export(report, first);
        exporter.export(report, second);

        assertEquals(first.toString(), second.toString());
    }

    @Test
    void capsRenderedRowsAndKeepsTotals() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/project"), Map.of(),
                List.of(violation("AEM-DEAD-001", "one", 1), violation("AEM-DEAD-001", "two", 2),
                        violation("AEM-DEAD-001", "three", 3)), 10);

        String html = new HtmlReportExporter(java.time.Clock.systemUTC(), 2).generateHtml(report);

        assertTrue(html.contains("1 more violation(s) omitted"), html);
        assertTrue(html.contains(">3<"), "severity/total KPI must still include all violations");
    }

    @Test
    void escapesViolationText() throws Exception {
        Violation xss = new Violation("AEM-SEC-001", Severity.ERROR, null,
                "<script>alert(1)</script>", "core/<bad>.java",
                new TextRegion(1, 1, 1, 10), null, null, List.of());
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/project"), Map.of(),
                List.of(xss), 10);

        String html = new HtmlReportExporter().generateHtml(report);

        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), html);
        assertTrue(html.contains("core/&lt;bad&gt;.java"), html);
    }

    @Test
    void duplicateBlocksAreRenderedWithKindAndPreview() throws Exception {
        CodeMetrics.DuplicateBlock duplicateBlock = new CodeMetrics.DuplicateBlock(
                "core/Dup.java", 3, 5, "core/Orig.java", 10, 12, 3, 42);
        CodeMetrics metrics = new CodeMetrics(2, 20, 2, 3, 1, 15.0,
                List.of(new CodeMetrics.ClassDuplication("Dup", "core/Dup.java", 3, 20, 15.0)),
                List.of(duplicateBlock));
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/project"), Map.of(),
                List.of(), 10, CoverageReport.notFound(), metrics);

        String html = new HtmlReportExporter(java.time.Clock.systemUTC(), 5000).generateHtml(report);

        assertTrue(html.contains("Duplicate Blocks (1)"), html);
        assertTrue(html.contains("core/Dup.java:3-5"), html);
        assertTrue(html.contains("core/Orig.java:10-12"), html);
        assertTrue(html.contains("Show duplicated lines"), html);
        assertTrue(html.contains("42"), html);
    }

    private Violation violation(String ruleId, String message, int line) {
        return new Violation(ruleId, Severity.WARNING, null, message, "core/A.java",
                new TextRegion(line, 1, line, 10), null, null, List.of());
    }
}
