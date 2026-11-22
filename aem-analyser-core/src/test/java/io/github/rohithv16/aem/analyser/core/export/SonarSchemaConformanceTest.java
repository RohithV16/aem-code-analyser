package io.github.rohithv16.aem.analyser.core.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural conformance checks for the SonarQube Generic Issue Import format.
 */
class SonarSchemaConformanceTest {

    @Test
    void emittedIssuesSatisfyRequiredSonarShape() throws Exception {
        Violation violation = new Violation(
                RuleId.AEM_SEC_001.id(), Severity.ERROR, "UNSAFE_HTL_CONTEXT_BYPASS",
                "Unsafe HTL context", "ui.apps/components/a/a.html",
                new TextRegion(5, 4, 5, 4), null, "Use html", List.of());
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(),
                List.of(violation), 1);

        StringWriter writer = new StringWriter();
        new SonarReportExporter().export(report, writer);
        JsonNode root = new ObjectMapper().readTree(writer.toString());
        JsonNode issues = root.get("issues");

        assertTrue(issues.isArray());
        assertEquals(1, issues.size());
        JsonNode issue = issues.get(0);
        assertEquals("aem-code-analyser", issue.get("engineId").asText());
        assertEquals(RuleId.AEM_SEC_001.id(), issue.get("ruleId").asText());
        assertTrue(Set.of("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO")
                .contains(issue.get("severity").asText()));
        assertTrue(Set.of("BUG", "VULNERABILITY", "CODE_SMELL")
                .contains(issue.get("type").asText()));

        JsonNode primary = issue.get("primaryLocation");
        assertFalse(primary.get("message").asText().isBlank());
        assertFalse(primary.get("filePath").asText().isBlank());
        JsonNode range = primary.get("textRange");
        assertTrue(range.get("startLine").asInt() >= 1);
        assertTrue(range.get("startColumn").asInt() >= 0);
        if (range.get("startLine").asInt() == range.get("endLine").asInt()) {
            assertTrue(range.get("endColumn").asInt() > range.get("startColumn").asInt());
        }
    }

    /**
     * Regression for #69: {@code SECURITY_HOTSPOT} is a model type, not a transport type. Sonar's
     * generic issue format accepts exactly three values, so every rule in the registry - including
     * AEM-QUAL-003 - must serialise to one of them.
     */
    @Test
    void everyRegistryRuleSerialisesToAnAllowedSonarType() throws Exception {
        List<Violation> violations = new java.util.ArrayList<>();
        for (var rule : io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry.all()) {
            violations.add(new Violation(rule.id(), Severity.INFO, null, "message",
                    "ui.apps/a.html", new TextRegion(1, 1, 1, 2), null, null, List.of()));
        }
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(), violations, 1);

        StringWriter writer = new StringWriter();
        new SonarReportExporter().export(report, writer);
        JsonNode issues = new ObjectMapper().readTree(writer.toString()).get("issues");

        Set<String> allowed = Set.of("BUG", "VULNERABILITY", "CODE_SMELL");
        String securityHotspotRuleType = null;
        for (JsonNode issue : issues) {
            String type = issue.get("type").asText();
            assertTrue(allowed.contains(type),
                    "rule " + issue.get("ruleId").asText() + " emitted invalid Sonar type " + type);
            if ("AEM-QUAL-003".equals(issue.get("ruleId").asText())) {
                securityHotspotRuleType = type;
            }
        }
        assertEquals("VULNERABILITY", securityHotspotRuleType,
                "a security-hotspot rule must be transported as a vulnerability");
    }
}
