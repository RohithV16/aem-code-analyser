package io.github.rohithv16.aem.analyser.core.config;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyserConfigTest {

    @TempDir
    Path root;

    @Test
    void loadsYamlAndAppliesEnablementAndSeverity() throws Exception {
        Files.writeString(root.resolve(AnalyserConfigLoader.DEFAULT_FILE), """
                schemaVersion: 1
                rules:
                  AEM-DEAD-003B:
                    enabled: false
                  AEM-SEC-001:
                    severity: WARNING
                allowlists:
                  services:
                    - com.example.LegacyService
                  configPaths:
                    - ui.config/**/config.local/**
                """);

        AnalyserConfig config = AnalyserConfigLoader.loadIfPresent(root);

        assertFalse(config.isEnabled("AEM-DEAD-003B"));
        assertTrue(config.isEnabled("AEM-DEAD-001"));
        assertTrue(config.allowlists().services().contains("com.example.LegacyService"));

        AnalysisReport report = new AnalysisReport(root, Map.of(), List.of(
                violation("AEM-DEAD-003B", Severity.INFO),
                violation("AEM-SEC-001", Severity.ERROR),
                violation("AEM-DEAD-001", Severity.ERROR)), 1);
        AnalysisReport adjusted = config.apply(report);

        assertEquals(2, adjusted.violations().size());
        assertTrue(adjusted.violations().stream()
                .anyMatch(v -> v.ruleId().equals("AEM-SEC-001") && v.severity() == Severity.WARNING));
    }

    @Test
    void loadsTheGateBlockFromTheSameFileAsTheRules() throws Exception {
        Files.writeString(root.resolve(AnalyserConfigLoader.DEFAULT_FILE), """
                schemaVersion: 1
                gate:
                  failOn: WARNING
                  failOnWarning: true
                  maxWarnings: 0
                  minCoverage: 80.0
                  maxDuplication: 3.0
                  formats: [CONSOLE, SARIF]
                  output: target/aem-analyser
                rules:
                  AEM-DEAD-003B:
                    enabled: false
                """);

        AnalyserConfig config = AnalyserConfigLoader.loadIfPresent(root);

        AnalyserConfig.Gate gate = config.gate();
        assertEquals("WARNING", gate.failOn());
        assertEquals(Boolean.TRUE, gate.failOnWarning());
        assertEquals(0, gate.maxWarnings());
        assertEquals(80.0, gate.minCoverage());
        assertEquals(3.0, gate.maxDuplication());
        assertEquals(List.of("CONSOLE", "SARIF"), gate.formats());
        assertEquals("target/aem-analyser", gate.output());
        // The gate is a separate layer: it must not disable or re-severity anything.
        assertFalse(config.isEnabled("AEM-DEAD-003B"));
    }

    @Test
    void aConfigurationWithoutAGateKeepsTheDefaults() throws Exception {
        Files.writeString(root.resolve(AnalyserConfigLoader.DEFAULT_FILE), """
                schemaVersion: 1
                rules:
                  AEM-DEAD-003B:
                    severity: INFO
                """);

        AnalyserConfig config = AnalyserConfigLoader.loadIfPresent(root);

        assertEquals(null, config.gate());
    }

    private Violation violation(String ruleId, Severity severity) {
        return new Violation(ruleId, severity, "X", "message", "core/A.java",
                new TextRegion(1, 1, 1, 2), null, null, List.of());
    }
}
