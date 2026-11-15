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
}
