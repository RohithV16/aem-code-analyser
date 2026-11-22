package io.github.rohithv16.aem.analyser.core.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rohithv16.aem.analyser.core.baseline.Fingerprint;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit behaviour of the neutral JSON report (#36). */
class JsonReportExporterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Violation violation(String ruleId, Severity severity, String message,
                                       String path, int line) {
        return new Violation(ruleId, severity, "ORPHANED_CONFIGURATION", message, path,
                new TextRegion(line, 3, line, 8), "com.example.Pid", "Remove the config", List.of());
    }

    private String export(AnalysisReport report) throws Exception {
        StringWriter writer = new StringWriter();
        new JsonReportExporter().export(report, writer);
        return writer.toString();
    }

    @Test
    void documentCarriesSchemaVersionToolAndSummary() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of("modules", 1), List.of(
                violation(RuleId.AEM_DEAD_001.id(), Severity.ERROR, "Orphaned", "ui.config/a.cfg.json", 4),
                violation(RuleId.AEM_DUP_002.id(), Severity.WARNING, "Duplicate", "ui.config/b.config", 9),
                violation(RuleId.AEM_QUAL_002.id(), Severity.INFO, "Info", "ui.apps/c.java", 2)), 1);

        JsonNode root = mapper.readTree(export(report));

        assertEquals(1, root.get("schemaVersion").asInt());
        assertEquals("aem-code-analyser", root.at("/tool/name").asText());
        assertFalse(root.at("/tool/version").asText().isBlank());
        assertEquals(3, root.at("/summary/total").asInt());
        assertEquals(1, root.at("/summary/error").asInt());
        assertEquals(1, root.at("/summary/warning").asInt());
        assertEquals(1, root.at("/summary/info").asInt());
        assertTrue(root.at("/summary/gate/status").asText().matches("[A-Z_]+"));
        assertEquals(3, root.get("violations").size());
    }

    @Test
    void violationCarriesTheDocumentedFieldsAndAFingerprint() throws Exception {
        Violation violation = violation(RuleId.AEM_DEAD_001.id(), Severity.ERROR,
                "Orphaned config: com.example.Pid", "ui.config/a.cfg.json", 4);
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(), List.of(violation), 1);

        JsonNode entry = mapper.readTree(export(report)).at("/violations/0");

        assertEquals(RuleId.AEM_DEAD_001.id(), entry.get("ruleId").asText());
        assertEquals("ERROR", entry.get("severity").asText());
        assertEquals("Orphaned config: com.example.Pid", entry.get("message").asText());
        assertEquals("ui.config/a.cfg.json", entry.get("filePath").asText());
        assertEquals(4, entry.get("startLine").asInt());
        assertEquals(3, entry.get("startColumn").asInt());
        assertEquals(4, entry.get("endLine").asInt());
        assertEquals(8, entry.get("endColumn").asInt());
        assertEquals("com.example.Pid", entry.get("targetPid").asText());
        assertEquals("Remove the config", entry.get("remediation").asText());
        assertEquals("ORPHANED_CONFIGURATION", entry.get("subtype").asText());
        assertEquals(Fingerprint.of(violation), entry.get("fingerprint").asText());
        assertFalse(entry.get("fingerprint").asText().isBlank());
        assertFalse(entry.at("/rule/name").asText().isBlank());
        assertEquals(RuleId.AEM_DEAD_001.id(), entry.at("/rule/id").asText());
        assertTrue(entry.at("/rule/tags").isArray());
        assertFalse(entry.at("/rule/helpUri").asText().isBlank());
    }

    @Test
    void emptyReportIsWellFormed() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(), List.of(), 0);

        JsonNode root = mapper.readTree(export(report));

        assertEquals(0, root.at("/summary/total").asInt());
        assertTrue(root.get("violations").isArray());
        assertEquals(0, root.get("violations").size());
    }

    /** An id produced by another engine has no registry descriptor; it must not fail. */
    @Test
    void unknownRuleIdStillSerialises() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(), List.of(
                violation("AEM-EXT-999", Severity.WARNING, "Duplication", "ui.apps/a.js", 1)), 1);

        JsonNode entry = mapper.readTree(export(report)).at("/violations/0");

        assertEquals("AEM-EXT-999", entry.at("/rule/id").asText());
        assertEquals("AEM-EXT-999", entry.at("/rule/name").asText());
        assertTrue(entry.at("/rule/tags").isArray());
    }
}
