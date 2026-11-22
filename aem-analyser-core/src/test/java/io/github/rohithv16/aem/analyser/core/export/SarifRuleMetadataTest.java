package io.github.rohithv16.aem.analyser.core.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDescriptor;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #20: GitHub code scanning ranks an alert off {@code properties.security-severity} and shows
 * nothing when it is absent, so every uploaded alert was unranked. These checks pin the ranking
 * metadata, the problem-severity fallback and the markdown help built from existing remediation.
 */
class SarifRuleMetadataTest {

    private static JsonNode rules() throws Exception {
        StringWriter writer = new StringWriter();
        new SarifReportExporter().export(
                new AnalysisReport(Path.of("/mock/project"), Map.of(), List.of(), 1), writer);
        return new ObjectMapper().readTree(writer.toString())
                .at("/runs/0/tool/driver/rules");
    }

    private static JsonNode find(JsonNode rules, String id) {
        for (JsonNode rule : rules) {
            if (id.equals(rule.path("id").asText())) {
                return rule;
            }
        }
        return null;
    }

    @Test
    void securityTaggedRulesCarryAnInRangeSecuritySeverity() throws Exception {
        JsonNode rules = rules();
        int checked = 0;
        for (JsonNode rule : rules) {
            boolean security = false;
            for (JsonNode tag : rule.path("properties").path("tags")) {
                security |= "security".equals(tag.asText());
            }
            if (!security) {
                continue;
            }
            checked++;
            JsonNode score = rule.path("properties").path("security-severity");
            assertTrue(score.isTextual() || score.isNumber(),
                    rule.path("id").asText() + " is security-tagged but has no security-severity");
            double value = Double.parseDouble(score.asText());
            assertTrue(value >= 0.1 && value <= 10.0,
                    "security-severity must be a GitHub CVSS-band score in 0.1–10.0, got " + value);
            assertTrue(rule.path("properties").path("problem").isMissingNode(),
                    "a security-ranked rule must not also advertise problem.severity");
        }
        assertTrue(checked >= 2, "expected the two security rules to be advertised, found " + checked);
    }

    @Test
    void nonSecurityRulesCarryProblemSeverityInsteadOfASecurityScore() throws Exception {
        JsonNode rules = rules();
        int checked = 0;
        for (JsonNode rule : rules) {
            if (!rule.path("properties").path("security-severity").isMissingNode()) {
                continue;
            }
            checked++;
            String problem = rule.path("properties").path("problem").path("severity").asText();
            assertTrue(List.of("error", "warning", "note").contains(problem),
                    rule.path("id").asText() + " must map to a SARIF problem severity, got '" + problem + "'");
            assertEquals(rule.path("defaultConfiguration").path("level").asText(), problem,
                    "problem.severity must agree with the rule's default level");
        }
        assertTrue(checked > 0, "expected non-security rules to be advertised");
    }

    @Test
    void everyRuleCarriesPrecisionAndMarkdownHelp() throws Exception {
        JsonNode rules = rules();
        for (JsonNode rule : rules) {
            String precision = rule.path("properties").path("precision").asText();
            assertTrue(List.of("high", "medium", "low").contains(precision),
                    rule.path("id").asText() + " has no valid precision, got '" + precision + "'");
            String markdown = rule.path("help").path("markdown").asText();
            assertFalse(markdown.isBlank(), rule.path("id").asText() + " must carry help.markdown");
            assertTrue(markdown.contains(rule.path("helpUri").asText()),
                    "help.markdown must link the rule documentation");
        }
    }

    @Test
    void helpMarkdownReusesTheRegistryRemediationText() throws Exception {
        RuleDescriptor descriptor = RuleRegistry.get("AEM-SEC-001");
        assertNotNull(descriptor.remediation());
        JsonNode rule = find(rules(), "AEM-SEC-001");
        assertNotNull(rule, "AEM-SEC-001 must be advertised");

        String markdown = rule.path("help").path("markdown").asText();
        assertTrue(markdown.contains(descriptor.remediation()),
                "help.markdown must reuse the registry remediation instead of a second copy");
        assertEquals("8.0", rule.path("properties").path("security-severity").asText());
    }
}
