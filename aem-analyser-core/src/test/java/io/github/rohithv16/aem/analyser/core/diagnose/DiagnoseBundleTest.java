package io.github.rohithv16.aem.analyser.core.diagnose;

import io.github.rohithv16.aem.analyser.core.config.AnalyserConfig;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisDiagnostic;
import io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #54: the diagnose bundle is local, redacted and reproducible. These checks pin the four
 * entries, the absence of the project's group id and package prefix, the redacted allowlists and
 * byte-identical output across runs.
 */
class DiagnoseBundleTest {

    @Test
    void writesFourEntriesWithoutTheProjectNamespace(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId>"
                        + "<artifactId>fixture</artifactId><version>1.0.0</version></project>");
        Path source = project.resolve("core/src/main/java/com/acme/Cards.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, String.join("\n",
                "package com.acme;",
                "",
                "import com.day.cq.wcm.api.PageManager;",
                "",
                "public class Cards {",
                "    private PageManager pageManager;",
                "}"));
        Violation finding = new Violation("AEM-QUAL-004", Severity.ERROR, "UNSAFE_COMPONENT_STATE",
                "OSGi component 'com.acme.Cards' declares field 'pageManager'",
                "core/src/main/java/com/acme/Cards.java", new TextRegion(6, 5, 6, 40),
                null, null, List.of());
        AnalyserConfig config = new AnalyserConfig(1,
                Map.of("AEM-QUAL-003", new AnalyserConfig.RuleOverride(true, Severity.INFO)),
                new AnalyserConfig.Allowlists(Set.of("com.acme.InternalService"),
                        Set.of("ui.config/**/config.local/**")));
        List<AnalysisDiagnostic> diagnostics = List.of(new AnalysisDiagnostic(
                DiagnosticKind.JAVA_PARSE_ERROR, "core/src/main/java/com/acme/Cards.java",
                "unexpected token in com.acme.Cards", 6));

        Path first = project.resolve("bundle/aem-analyser-diagnose-AEM-QUAL-004.zip");
        DiagnoseBundle.write(project, finding, RuleRegistry.get("AEM-QUAL-004"), config, diagnostics, first);

        Map<String, String> entries = readZip(first);
        assertEquals(List.of("issue.md", "finding.json", "excerpt.txt", "environment.json"),
                List.copyOf(entries.keySet()));
        for (String entry : entries.values()) {
            assertFalse(entry.contains("com.acme"),
                    "the project namespace must not survive in the bundle:\n" + entry);
            assertFalse(entry.contains("com/acme"),
                    "the path form of the package must not survive either:\n" + entry);
        }
        assertTrue(entries.get("excerpt.txt").contains("private PageManager pageManager;"),
                "the offending line must survive verbatim:\n" + entries.get("excerpt.txt"));
        assertTrue(entries.get("issue.md").contains("aem-analyser --diagnose"));
        assertTrue(entries.get("finding.json").contains("<redacted: 1 entries>"),
                "allowlist values must be replaced with counts: " + entries.get("finding.json"));
        assertTrue(entries.get("finding.json").contains("\"startLine\" : 6"));

        Path second = project.resolve("bundle/second.zip");
        DiagnoseBundle.write(project, finding, RuleRegistry.get("AEM-QUAL-004"), config, diagnostics, second);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second),
                "the same finding must produce byte-identical bundles (fixed zip timestamps)");
    }

    private static Map<String, String> readZip(Path zipFile) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
