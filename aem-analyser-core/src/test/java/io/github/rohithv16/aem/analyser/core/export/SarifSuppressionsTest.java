package io.github.rohithv16.aem.analyser.core.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rohithv16.aem.analyser.core.baseline.Baseline;
import io.github.rohithv16.aem.analyser.core.baseline.BaselineEntry;
import io.github.rohithv16.aem.analyser.core.baseline.BaselineFilter;
import io.github.rohithv16.aem.analyser.core.baseline.Fingerprint;
import io.github.rohithv16.aem.analyser.core.baseline.InlineSuppressions;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AcceptanceOrigin;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #17: an accepted finding must stay in the artefact as a suppressed result at its real level,
 * so consumers read "exists, accepted" instead of "0 problems" (baseline) or nothing at all (inline
 * directives, which used to be filtered before any exporter ran).
 */
class SarifSuppressionsTest {

    private static Violation violation(String ruleId, Severity severity, String subtype, String file,
                                       int line, String message) {
        return new Violation(ruleId, severity, subtype, message, file,
                new TextRegion(line, 3, line, 12), "com.example.Pid", "Fix it.", List.of());
    }

    private static JsonNode export(AnalysisReport report) throws Exception {
        StringWriter writer = new StringWriter();
        new SarifReportExporter().export(report, writer);
        return new ObjectMapper().readTree(writer.toString()).at("/runs/0");
    }

    private static JsonNode resultFor(JsonNode run, String ruleId) {
        for (JsonNode result : run.path("results")) {
            if (ruleId.equals(result.path("ruleId").asText())) {
                return result;
            }
        }
        return null;
    }

    @Test
    void baselinedFindingIsExportedSuppressedAtItsRealLevel(@TempDir Path root) throws Exception {
        Violation error = violation("AEM-QUAL-004", Severity.ERROR, "SESSION_IN_FIELD",
                "core/src/main/java/A.java", 42, "Session bound to a component field");
        Violation warning = violation("AEM-DEAD-001", Severity.WARNING, "ORPHANED_CONFIGURATION",
                "ui.config/com.example.Foo.cfg.json", 7, "Orphaned OSGi configuration");
        AnalysisReport report = new AnalysisReport(root, Map.of(), List.of(error, warning), 1);
        Baseline baseline = new Baseline(Baseline.CURRENT_SCHEMA_VERSION, "test",
                List.of(new BaselineEntry(Fingerprint.of(error), "AEM-QUAL-004",
                        "core/src/main/java/A.java", "SESSION_IN_FIELD")));

        AnalysisReport filtered = BaselineFilter.filter(report, baseline);

        // Gate-visible list keeps its old meaning: only the actionable finding is counted.
        assertEquals(1, filtered.violations().size());
        assertEquals(0, filtered.countBySeverity(Severity.ERROR),
                "an accepted error must not reach the gate: the exit code may not move");
        assertEquals(1, filtered.acceptedViolations().size());
        assertEquals(AcceptanceOrigin.BASELINE, filtered.acceptedViolations().get(0).acceptance());

        JsonNode run = export(filtered);
        assertEquals(2, run.path("results").size(),
                "the artefact must keep both the actionable and the accepted finding");

        JsonNode accepted = resultFor(run, "AEM-QUAL-004");
        assertNotNull(accepted, "the accepted finding must still be in the report");
        assertEquals("error", accepted.path("level").asText(),
                "an accepted finding keeps the level the rule produced");
        assertEquals("unchanged", accepted.path("baselineState").asText());
        assertEquals("accepted", accepted.path("suppressions").path(0).path("status").asText());
        assertEquals("external", accepted.path("suppressions").path(0).path("kind").asText());
        assertTrue(accepted.path("suppressions").path(0).path("justification").asText()
                        .startsWith("baseline:"), "the justification must name the baseline fingerprint");
        assertEquals(42, accepted.at("/locations/0/physicalLocation/region/startLine").asInt(),
                "beginLine must survive suppression");
        assertEquals("SESSION_IN_FIELD", accepted.path("properties").path("subtype").asText(),
                "suppression must annotate, not rewrite, the finding");

        JsonNode actionable = resultFor(run, "AEM-DEAD-001");
        assertTrue(actionable.path("suppressions").isMissingNode(),
                "an actionable finding must not carry a suppression");
        assertTrue(actionable.path("baselineState").isMissingNode());
    }

    @Test
    void inlineDirectiveProducesAnInSourceAcceptedSuppression(@TempDir Path root) throws Exception {
        Path source = root.resolve("core/src/main/java/Service.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, String.join("\n",
                "class Service {",
                "    // aem-analyser-disable-next-line AEM-DEAD-001",
                "    void run() { }",
                "}"));

        Violation suppressed = violation("AEM-DEAD-001", Severity.WARNING, "ORPHANED_CONFIGURATION",
                "core/src/main/java/Service.java", 3, "Unused service");
        Violation actionable = violation("AEM-DUP-001", Severity.WARNING, "DUPLICATE_SERVLET",
                "core/src/main/java/Service.java", 3, "Duplicate servlet registration");

        List<Violation> annotated = InlineSuppressions.annotate(root, List.of(suppressed, actionable));
        assertEquals(AcceptanceOrigin.INLINE, annotated.get(0).acceptance());
        assertEquals(AcceptanceOrigin.NONE, annotated.get(1).acceptance(),
                "a directive naming one rule must not hide another");

        AnalysisReport report = new AnalysisReport(root, Map.of(),
                List.of(annotated.get(1)), 1).withAcceptedViolations(List.of(annotated.get(0)));
        JsonNode run = export(report);

        JsonNode accepted = resultFor(run, "AEM-DEAD-001");
        assertNotNull(accepted, "inline-suppressed findings must reach the exporter");
        assertEquals("warning", accepted.path("level").asText());
        assertEquals("unchanged", accepted.path("baselineState").asText());
        assertEquals("inSource", accepted.path("suppressions").path(0).path("kind").asText());
        assertEquals("accepted", accepted.path("suppressions").path(0).path("status").asText());
        assertEquals(3, accepted.at("/locations/0/physicalLocation/region/startLine").asInt());
    }

    /**
     * {@code --baseline-report} keeps an INFO copy in the gate-visible list for renderers that cannot
     * express a suppression. SARIF must emit the untouched real-level copy once, not both.
     */
    @Test
    void legacyBaselineReportModeDoesNotDuplicateTheSuppressedResult(@TempDir Path root) {
        Violation error = violation("AEM-QUAL-004", Severity.ERROR, "SESSION_IN_FIELD",
                "core/src/main/java/A.java", 5, "Session bound to a component field");
        AnalysisReport report = new AnalysisReport(root, Map.of(), List.of(error), 1);
        Baseline baseline = new Baseline(Baseline.CURRENT_SCHEMA_VERSION, "test",
                List.of(new BaselineEntry(Fingerprint.of(error), "AEM-QUAL-004",
                        "core/src/main/java/A.java", "SESSION_IN_FIELD")));

        AnalysisReport filtered = BaselineFilter.filter(report, baseline, true);
        assertEquals(1, filtered.violations().size(), "legacy mode still lists the INFO copy");

        JsonNode results;
        try {
            results = export(filtered).path("results");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(1, results.size(), "SARIF must not emit the INFO copy and the real one");
        assertEquals("error", results.get(0).path("level").asText());
        assertEquals("accepted", results.get(0).path("suppressions").path(0).path("status").asText());
        assertFalse(filtered.acceptedViolations().isEmpty());
    }
}
