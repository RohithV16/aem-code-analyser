package io.github.rohithv16.aem.analyser.core.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AcceptanceOrigin;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDescriptor;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural conformance checks for SARIF 2.1.0 required properties and coordinate invariants.
 */
class SarifSchemaConformanceTest {

    @Test
    void emittedDocumentSatisfiesRequiredSarifShape() throws Exception {
        Violation violation = new Violation(
                RuleId.AEM_DEAD_001.id(), Severity.ERROR, "ORPHANED_CONFIGURATION",
                "Orphaned configuration", "ui.config/a.cfg.json",
                new TextRegion(3, 2, 3, 2), "com.example.Pid", "Remove it", List.of());
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of("modules", 1),
                List.of(violation), 1);

        StringWriter writer = new StringWriter();
        new SarifReportExporter().export(report, writer);
        JsonNode sarif = new ObjectMapper().readTree(writer.toString());

        assertEquals("2.1.0", sarif.get("version").asText());
        assertTrue(sarif.hasNonNull("$schema"));
        assertTrue(sarif.at("/runs").isArray());
        assertEquals(1, sarif.at("/runs").size());

        JsonNode run = sarif.at("/runs/0");
        JsonNode driver = run.at("/tool/driver");
        assertFalse(driver.at("/name").asText().isBlank());
        assertFalse(driver.at("/version").asText().isBlank());
        assertTrue(driver.at("/rules").isArray());
        assertFalse(driver.at("/rules").isEmpty());

        JsonNode result = run.at("/results/0");
        assertFalse(result.at("/ruleId").asText().isBlank());
        assertTrue(result.at("/ruleIndex").asInt() >= 0);
        assertTrue(result.at("/ruleIndex").asInt() < driver.at("/rules").size());
        assertTrue(List.of("error", "warning", "note").contains(result.at("/level").asText()));
        assertFalse(result.at("/message/text").asText().isBlank());

        JsonNode location = result.at("/locations/0/physicalLocation");
        assertFalse(location.at("/artifactLocation/uri").asText().isBlank());
        assertTrue(location.at("/region/startLine").asInt() >= 1);
        assertTrue(location.at("/region/startColumn").asInt() >= 1);
        if (location.at("/region/startLine").asInt() == location.at("/region/endLine").asInt()) {
            assertTrue(location.at("/region/endColumn").asInt()
                    > location.at("/region/startColumn").asInt());
        }
    }

    @Test
    void relatedLocationsAreNotDoubleWrapped() throws Exception {
        Violation violation = new Violation(
                RuleId.AEM_DUP_002.id(), Severity.WARNING, "DUPLICATE_OSGI_CONFIG",
                "Duplicate configuration", "ui.config/a.config",
                new TextRegion(1, 1, 1, 1), "com.example.Pid", "Merge them",
                List.of(new RelatedLocation("First definition", "ui.config/a.cfg.json",
                        new TextRegion(1, 1, 1, 1))));
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of("modules", 1),
                List.of(violation), 1);

        StringWriter writer = new StringWriter();
        new SarifReportExporter().export(report, writer);
        JsonNode sarif = new ObjectMapper().readTree(writer.toString());

        JsonNode related = sarif.at("/runs/0/results/0/relatedLocations/0");
        assertFalse(related.isMissingNode(), "Related location must be emitted");
        assertEquals(1, related.at("/id").asInt());
        assertTrue(related.hasNonNull("physicalLocation"), "Related location needs a physicalLocation");
        assertFalse(related.at("/physicalLocation").has("physicalLocation"),
                "physicalLocation must not contain another physicalLocation wrapper");
        assertEquals("ui.config/a.cfg.json",
                related.at("/physicalLocation/artifactLocation/uri").asText());
        assertEquals(1, related.at("/physicalLocation/region/startLine").asInt());
    }

    /**
     * Issue #17: a suppressed result must stay schema-valid — {@code suppressions[].kind} is an
     * enumerated SARIF value, {@code status} is accepted/rejected/underReview, and a suppression with
     * a status must carry a justification. {@code baselineState} is likewise enumerated.
     */
    @Test
    void suppressedResultsKeepTheSarifSuppressionShape() throws Exception {
        Violation accepted = new Violation(
                RuleId.AEM_QUAL_004.id(), Severity.ERROR, "SESSION_IN_FIELD",
                "Session bound to a component field", "core/src/main/java/A.java",
                new TextRegion(9, 5, 9, 20), null, "Do not store it.", List.of())
                .withAcceptance(AcceptanceOrigin.BASELINE);
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(), List.of(), 1)
                .withAcceptedViolations(List.of(accepted));

        StringWriter writer = new StringWriter();
        new SarifReportExporter().export(report, writer);
        JsonNode result = new ObjectMapper().readTree(writer.toString()).at("/runs/0/results/0");

        assertEquals("unchanged", result.path("baselineState").asText());
        JsonNode suppression = result.path("suppressions").path(0);
        assertTrue(List.of("inSource", "external").contains(suppression.path("kind").asText()),
                "SARIF suppression kind is an enumerated value");
        assertTrue(List.of("accepted", "rejected", "underReview")
                        .contains(suppression.path("status").asText()),
                "SARIF suppression status is an enumerated value");
        assertFalse(suppression.path("justification").asText().isBlank(),
                "a suppression with a status should explain itself");
        assertEquals(9, result.at("/locations/0/physicalLocation/region/startLine").asInt());
        assertEquals("error", result.path("level").asText());
    }

    @Test
    void ruleCatalogueKeepsDeclarationOrderForStableRuleIndexes() {
        List<String> actual = RuleRegistry.all().stream().map(RuleDescriptor::id).toList();
        List<String> expected = Arrays.stream(RuleId.values()).map(RuleId::id).toList();

        assertEquals(expected, actual,
                "Rule catalogue order must be stable; it drives SARIF ruleIndex values");
    }
}
