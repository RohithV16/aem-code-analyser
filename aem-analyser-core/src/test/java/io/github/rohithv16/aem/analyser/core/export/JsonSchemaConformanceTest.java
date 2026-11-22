package io.github.rohithv16.aem.analyser.core.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract checks for the JSON document: the shape a dashboard/gate script reads, plus the byte
 * stability two runs over an unchanged tree must have.
 */
class JsonSchemaConformanceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 36 findings with the production mix: 7 ERROR, 28 WARNING, 1 INFO. */
    private static AnalysisReport portfolioShapedReport() {
        List<Violation> violations = new ArrayList<>();
        List<String> ruleIds = new ArrayList<>(RuleRegistry.all().stream()
                .map(rule -> rule.id()).distinct().toList());
        for (int i = 0; i < 36; i++) {
            Severity severity = i < 7 ? Severity.ERROR
                    : i < 35 ? Severity.WARNING : Severity.INFO;
            violations.add(new Violation(ruleIds.get(i % ruleIds.size()), severity, null,
                    "Finding number " + i, "ui.apps/components/c" + i + ".html",
                    new TextRegion(i + 1, 1, i + 1, 10), null, null, List.of()));
        }
        return new AnalysisReport(Path.of("/mock"), Map.of("modules", 1), violations, 12);
    }

    private static String export(AnalysisReport report) throws Exception {
        StringWriter writer = new StringWriter();
        new JsonReportExporter().export(report, writer);
        return writer.toString();
    }

    @Test
    void summaryCountsMatchTheFindings() throws Exception {
        JsonNode root = mapper.readTree(export(portfolioShapedReport()));

        assertEquals(36, root.at("/violations").size());
        assertEquals(36, root.at("/summary/total").asInt());
        assertEquals(7, root.at("/summary/error").asInt());
        assertEquals(28, root.at("/summary/warning").asInt());
        assertEquals(1, root.at("/summary/info").asInt());
        assertEquals("FAILED", root.at("/summary/gate/status").asText());
    }

    @Test
    void everyFindingHasAStableNonEmptyFingerprint() throws Exception {
        String json = export(portfolioShapedReport());
        JsonNode violations = mapper.readTree(json).get("violations");

        int found = 0;
        for (JsonNode violation : violations) {
            String fingerprint = violation.get("fingerprint").asText("");
            assertTrue(fingerprint.matches("[0-9a-f]{16}"),
                    "fingerprint must be the 16-hex-char baseline id, got '" + fingerprint + "'");
            found++;
        }
        assertEquals(36, found);
    }

    @Test
    void twoRunsSerialiseIdentically() throws Exception {
        AnalysisReport report = portfolioShapedReport();
        String first = export(report);
        String second = export(report);

        assertEquals(first, second);
        assertEquals(sha256(first), sha256(second));
        assertTrue(first.contains("\"schemaVersion\""));
    }

    /** Key order, not just key presence: a reordered document breaks diffing just as a value does. */
    @Test
    void documentKeysAppearInDocumentedOrder() throws Exception {
        String json = export(portfolioShapedReport());
        int schema = json.indexOf("\"schemaVersion\"");
        int tool = json.indexOf("\"tool\"");
        int summary = json.indexOf("\"summary\"");
        int violations = json.indexOf("\"violations\"");

        assertTrue(schema >= 0 && schema < tool, "schemaVersion must come first");
        assertTrue(tool < summary, "tool must precede summary");
        assertTrue(summary < violations, "summary must precede violations");
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
