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
