package io.github.rohithv16.aem.analyser.core.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.CodeMetrics;
import io.github.rohithv16.aem.analyser.core.model.report.CoverageReport;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisDiagnostic;
import io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SarifReportExporterTest {

    @Test
    void emitsProjectMetadataRuleIndexesAndValidRegions() throws Exception {
        Violation violation = new Violation(
                RuleId.AEM_DEAD_001.id(), Severity.ERROR, "ORPHANED_CONFIGURATION",
                "Orphaned configuration", "ui.config/a.cfg.json",
                new TextRegion(5, 3, 5, 3), null, "Remove it.", List.of());
        AnalysisReport report = new AnalysisReport(Path.of("/mock/project"), Map.of("modules", 1),
                List.of(violation), 10);

        StringWriter writer = new StringWriter();
        new SarifReportExporter().export(report, writer);
        JsonNode sarif = new ObjectMapper().readTree(writer.toString());

        JsonNode driver = sarif.at("/runs/0/tool/driver");
        assertEquals("https://github.com/rohithv16/aem-code-analyser", driver.get("informationUri").asText());
        assertNotEquals("1.0.0", driver.get("version").asText());
        assertFalse(driver.get("version").asText().contains("${"), "version must be filtered");
        assertTrue(driver.get("rules").size() > 0);
        assertTrue(driver.get("rules").get(0).has("helpUri"));

        JsonNode result = sarif.at("/runs/0/results/0");
        int ruleIndex = result.get("ruleIndex").asInt();
        assertTrue(ruleIndex >= 0 && ruleIndex < driver.get("rules").size());
        assertEquals(RuleId.AEM_DEAD_001.id(), driver.get("rules").get(ruleIndex).get("id").asText());

        JsonNode region = result.at("/locations/0/physicalLocation/region");
        assertTrue(region.get("endColumn").asInt() > region.get("startColumn").asInt(),
                "SARIF single-line regions must not be empty");
    }

    /**
     * SARIF is how consumers know a rule exists but cannot fire. Only retired ids are disabled: an id
     * produced by another engine (AEM-DUP-010 via PMD CPD) must stay enabled, otherwise tooling
     * stops displaying its findings.
     */
    @Test
    void disablesOnlyRetiredRuleDescriptors() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/mock/project"), Map.of("modules", 1),
                List.of(), 10);
        StringWriter out = new StringWriter();
        new SarifReportExporter().export(report, out);
        JsonNode rules = new ObjectMapper().readTree(out.toString())
                .path("runs").get(0).path("tool").path("driver").path("rules");

        JsonNode retired = null;
        JsonNode producedElsewhere = null;
        for (JsonNode rule : rules) {
            if (RuleId.AEM_QUAL_006.id().equals(rule.path("id").asText())) {
                retired = rule;
            } else if (RuleId.AEM_DUP_010.id().equals(rule.path("id").asText())) {
                producedElsewhere = rule;
            }
        }
        assertTrue(retired != null, "AEM-QUAL-006 descriptor must still be advertised");
        assertFalse(retired.path("defaultConfiguration").path("enabled").asBoolean(true),
                "a retired rule must be disabled");
        assertTrue(producedElsewhere != null, "AEM-DUP-010 descriptor must be advertised");
        assertTrue(producedElsewhere.path("defaultConfiguration").path("enabled").asBoolean(true),
                "a rule produced by another engine must stay enabled");
    }

    /**
     * Regression for #6: two exports of the same immutable report must be byte-identical. The
     * originalUriBaseIds child used nested {@code Map.of}, whose iteration order is randomised per
     * JVM, so identical runs differed only in whether "uri" or "description" came first.
     */
    @Test
    void identicalReportsExportToIdenticalBytes() throws Exception {
        Violation violation = new Violation(
                RuleId.AEM_QUAL_003.id(), Severity.WARNING, "SLING_SCHEDULER",
                "Whiteboard scheduler usage", "core/src/main/java/A.java",
                new TextRegion(7, 5, 7, 9), null, null, List.of());
        AnalysisReport report = new AnalysisReport(Path.of("/mock/project"), Map.of("modules", 1),
                List.of(violation), 10);

        String first = export(report);
        String second = export(report);

        assertEquals(first, second, "SARIF bytes must be stable across exports");
    }

    /**
     * Pins the canonical object order directly: byte-equality of two exports in one JVM cannot
     * catch {@code Map.of} (its salt is fixed for the JVM), so assert the documented key order of
     * the two multi-entry objects that used to be unordered.
     */
    @Test
    void serialisesNestedObjectsInCanonicalKeyOrder() throws Exception {
        Violation violation = new Violation(
                RuleId.AEM_DEAD_001.id(), Severity.ERROR, "ORPHANED_CONFIGURATION",
                "Orphaned configuration", "ui.config/a.cfg.json",
                new TextRegion(1, 1, 1, 10), null, null, List.of());
        AnalysisReport report = new AnalysisReport(Path.of("/mock/project"), Map.of("modules", 1),
                List.of(violation), 10);

        String sarif = export(report);

        int baseBlock = sarif.indexOf("\"originalUriBaseIds\"");
        assertTrue(baseBlock >= 0);
        int uri = sarif.indexOf("\"uri\"", baseBlock);
        int description = sarif.indexOf("\"description\"", baseBlock);
        assertTrue(uri >= 0 && description >= 0);
        assertTrue(uri < description, "\"uri\" must precede \"description\" in originalUriBaseIds");
    }

    private static String export(AnalysisReport report) throws Exception {
        StringWriter writer = new StringWriter();
        new SarifReportExporter().export(report, writer);
        return writer.toString();
    }

    @Test
    void emitsToolExecutionNotificationsForDiagnostics() throws Exception {
        AnalysisDiagnostic diagnostic = AnalysisDiagnostic.of(
                DiagnosticKind.JAVA_PARSE_ERROR, Path.of("/mock/Broken.java"), "unexpected token");
        AnalysisReport report = new AnalysisReport(Path.of("/mock/project"), Map.of(), List.of(), 1,
                CoverageReport.notFound(), new CodeMetrics(0, 0, 0, 0, 0, 0.0), List.of(diagnostic));

        StringWriter writer = new StringWriter();
        new SarifReportExporter().export(report, writer);

        JsonNode notification = new ObjectMapper().readTree(writer.toString())
                .at("/runs/0/invocations/0/toolExecutionNotifications/0");
        assertEquals("warning", notification.get("level").asText());
        assertTrue(notification.get("message").get("text").asText().contains("Broken.java"));
    }
}
