package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisDiagnostic;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.CodeMetrics;
import io.github.rohithv16.aem.analyser.core.model.report.CoverageReport;
import io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind;
import io.github.rohithv16.aem.analyser.core.model.report.GateVerdict;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Reproducible mode: the timestamp is formatted in the fixed clock's zone (never the machine's)
     * and the wall-clock duration is dropped, so {@code sha256sum} of the report is a cache key.
     */
    @Test
    void reproducibleExportOmitsDurationAndIgnoresTheLocalTimezone() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/project"), Map.of(),
                List.of(violation("AEM-DEAD-001", "message", 1)), 1250);
        java.time.Clock fixed = java.time.Clock.fixed(
                java.time.Instant.ofEpochSecond(1_700_000_000L), java.time.ZoneOffset.UTC);

        String html = new HtmlReportExporter(fixed, 5000, List.of(), true).generateHtml(report);

        assertTrue(html.contains("Generated 2023-11-14 22:13:20 UTC"), html);
        assertFalse(html.contains("Analysis 1.25s"), html);
        assertEquals(html, new HtmlReportExporter(fixed, 5000, List.of(), true).generateHtml(report));
    }

    /** Without a fixed clock the duration is useful context and stays. */
    @Test
    void liveExportKeepsTheDuration() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/project"), Map.of(),
                List.of(violation("AEM-DEAD-001", "message", 1)), 1250);

        String html = new HtmlReportExporter(java.time.Clock.systemUTC(), 5000).generateHtml(report);

        assertTrue(html.contains("Analysis 1.25s"), html);
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

    @Test
    void sonarRulesLinkToSonarSourceInsteadOfAMissingAnchor() {
        Violation sonar = new Violation("java:S2208", Severity.WARNING, null, "Wildcard import",
                "core/A.java", new TextRegion(1, 1, 1, 5), null, null, List.of());
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/project"), Map.of(),
                List.of(sonar), 10);

        String html = new HtmlReportExporter().generateHtml(report);

        assertTrue(html.contains("https://rules.sonarsource.com/java/rule/S2208/"), html);
        assertFalse(html.contains("issues/#java:s2208"),
                "the issue reference has no anchor for embedded SonarJava rules");
    }

    @Test
    void everyAemRuleLinksToAnAnchorTheSiteActuallyPublishes() throws Exception {
        var output = java.nio.file.Files.createTempDirectory("rule-docs");
        try {
            io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDocsGenerator.writeAll(output);
            String issues = java.nio.file.Files.readString(output.resolve("issues/index.html"));

            for (io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDescriptor rule
                    : io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry.all()) {
                Violation violation = new Violation(rule.id(), rule.defaultSeverity(), null, "message",
                        "core/A.java", new TextRegion(1, 1, 1, 5), null, null, List.of());
                String html = new HtmlReportExporter()
                        .generateHtml(new AnalysisReport(Path.of("/workspace/project"), Map.of(),
                                List.of(violation), 10));

                String anchor = rule.id().toLowerCase(java.util.Locale.ROOT);
                assertTrue(html.contains("issues/#" + anchor),
                        "dashboard must link " + rule.id() + " to its published anchor");
                assertTrue(issues.contains("id=\"" + anchor + "\""),
                        "issue reference must publish the anchor for " + rule.id());
            }
        } finally {
            try (var files = java.nio.file.Files.walk(output)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        java.nio.file.Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                        // scratch directory
                    }
                });
            }
        }
    }

    private Violation violation(String ruleId, String message, int line) {
        return new Violation(ruleId, Severity.WARNING, null, message, "core/A.java",
                new TextRegion(line, 1, line, 10), null, null, List.of());
    }

    @Test
    void reportOnlyRunDoesNotClaimAFailedGate() {
        Violation error = new Violation("AEM-DEAD-004", Severity.ERROR, null, "missing class",
                "core/A.java", new TextRegion(3, 1, 3, 10), null, null, List.of());
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/project"), Map.of(),
                List.of(error), 10)
                .withGateVerdict(GateVerdict.notEnforced("report-only run (--fail-on NONE)"));

        String html = new HtmlReportExporter().generateHtml(report);

        assertTrue(html.contains("GATE NOT ENFORCED"), html);
        assertFalse(html.contains("QUALITY GATE FAILED"),
                "a report-only run must not print a failure the exit code does not signal");
    }

    @Test
    void diagnosticsAreRenderedInRunHealthAndEscaped() {
        AnalysisDiagnostic diagnostic = new AnalysisDiagnostic(
                DiagnosticKind.JAVA_PARSE_ERROR, "core/Bad.java", "<script>boom</script>", 7);
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/project"), Map.of(),
                List.of(violation("AEM-DEAD-001", "message", 1)), 10,
                CoverageReport.notFound(), new CodeMetrics(0, 0, 0, 0, 0, 0.0), List.of(diagnostic));

        String html = new HtmlReportExporter().generateHtml(report);

        assertTrue(html.contains("Run Diagnostics (1)"), html);
        assertTrue(html.contains("JAVA_PARSE_ERROR"), html);
        assertTrue(html.contains("core/Bad.java:7"), html);
        assertTrue(html.contains("&lt;script&gt;boom&lt;/script&gt;"), html);
        assertFalse(html.contains("<script>boom</script>"), "diagnostic text must be escaped");
        assertTrue(html.contains("PASSED WITH 1 DIAGNOSTIC(S)"),
                "diagnostics must not look like a clean pass");
    }

    @Test
    void emptyDiagnosticsShowACleanState() {
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/project"), Map.of(),
                List.of(violation("AEM-DEAD-001", "message", 1)), 10);

        String html = new HtmlReportExporter().generateHtml(report);

        assertTrue(html.contains("No parser or scan diagnostics"), html);
        assertTrue(html.contains("No findings are suppressed by a baseline"), html);
    }

    @Test
    void baselineAcceptedFindingsAreSeparatedFromActiveTotals() {
        Violation accepted = new Violation("AEM-DEAD-001", Severity.INFO, "SUPPRESSED_BY_BASELINE",
                "[baseline] accepted finding", "ui.config/accepted.cfg.json",
                new TextRegion(1, 1, 1, 10), "com.example.Pid", "remove it", List.of());
        Violation active = violation("AEM-CLOUD-001", "active finding", 2);
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/project"), Map.of(),
                List.of(active, accepted), 10);

        String html = new HtmlReportExporter().generateHtml(report);

        assertTrue(html.contains("Violations Explorer (1)"), "active findings drive the explorer count");
        assertTrue(html.contains("Run Health (1)"), "accepted findings are counted in Run Health");
        assertTrue(html.contains("Accepted by Baseline"), html);
        assertTrue(html.contains("[baseline] accepted finding"), html);
        assertTrue(html.contains("ui.config/accepted.cfg.json:1"), html);
    }

    @Test
    void dashboardExposesExplorerExportAndAccessibilityControls() {
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/project"), Map.of(),
                List.of(violation("AEM-DEAD-001", "message", 1)), 10);

        String html = new HtmlReportExporter().generateHtml(report);

        assertTrue(html.contains("id=\"themeToggle\""), html);
        assertTrue(html.contains("id=\"groupBy\""), html);
        assertTrue(html.contains("id=\"exportCsv\""), html);
        assertTrue(html.contains("id=\"exportJson\""), html);
        assertTrue(html.contains("id=\"copyMarkdown\""), html);
        assertTrue(html.contains("id=\"copyLink\""), html);
        assertTrue(html.contains("id=\"resultCount\""), html);
        assertTrue(html.contains("data-sort=\"rule\""), html);
        assertTrue(html.contains("aria-live=\"polite\""), html);
        assertTrue(html.contains("role=\"tablist\""), html);
        assertTrue(html.contains("aria-selected=\"true\""), html);
        assertTrue(html.contains("@media print"), html);
        assertTrue(html.contains("prefers-color-scheme: dark"), html);
        assertTrue(html.contains("<noscript>"), html);
    }

    @Test
    void reportHeadCarriesSocialMetadataAndStaysOutOfTheIndex() {
        Violation error = new Violation("AEM-CLOUD-001", Severity.ERROR, null, "plaintext secret",
                "ui.config/a.cfg.json", new TextRegion(1, 1, 1, 10), null, null, List.of());
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/portfolio"), Map.of(),
                List.of(error, violation("AEM-PERF-001", "unclosed resolver", 2)), 10);

        String html = new HtmlReportExporter(java.time.Clock.systemUTC(), 5000, List.of(),
                "https://rohithv16.github.io/aem-code-analyser/demo/").generateHtml(report);

        assertTrue(html.contains("<meta name=\"robots\" content=\"noindex\">"), html);
        assertTrue(html.contains("<meta name=\"description\" content=\"AEM Code Analyser report — 2 findings "
                + "(1 error, 1 warning) for portfolio.\">"), html);
        assertTrue(html.contains("<meta property=\"og:title\" content=\"AEM Code Analyser — 2 findings\">"), html);
        assertTrue(html.contains("<meta property=\"og:description\""), html);
        assertTrue(html.contains("<meta property=\"og:url\" content=\"https://rohithv16.github.io/"
                + "aem-code-analyser/demo/\">"), html);
        assertTrue(html.contains("<link rel=\"canonical\" href=\"https://rohithv16.github.io/"
                + "aem-code-analyser/demo/\">"), html);
        assertTrue(html.contains("<meta name=\"twitter:card\" content=\"summary\">"), html);
    }

    @Test
    void reportWithoutAPublishedUrlEmitsNoCanonical() {
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/project"), Map.of(),
                List.of(violation("AEM-DEAD-001", "message", 1)), 10);

        String html = new HtmlReportExporter(java.time.Clock.systemUTC(), 5000, List.of(), null)
                .generateHtml(report);

        assertFalse(html.contains("rel=\"canonical\""), "a build artefact has no canonical URL");
        assertFalse(html.contains("og:url\""), "og:url without a published location would be a 404");
        assertTrue(html.contains("noindex"), "an unpublished report must still stay out of the index");
    }

    @Test
    void violationValuesAreEscapedIntoDataAttributes() {
        Violation xss = new Violation("AEM-SEC-001", Severity.ERROR, null,
                "quote \" and <tag>", "core/<bad>.java",
                new TextRegion(1, 1, 1, 10), null, "fix \"it\"", List.of());
        AnalysisReport report = new AnalysisReport(Path.of("/workspace/project"), Map.of(),
                List.of(xss), 10);

        String html = new HtmlReportExporter().generateHtml(report);

        assertTrue(html.contains("data-message=\"quote &quot; and &lt;tag&gt;\""), html);
        assertTrue(html.contains("data-remediation=\"fix &quot;it&quot;\""), html);
    }
}
