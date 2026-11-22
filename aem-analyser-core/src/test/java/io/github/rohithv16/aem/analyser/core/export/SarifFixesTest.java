package io.github.rohithv16.aem.analyser.core.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rohithv16.aem.analyser.core.fix.AutoFixEngine;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SARIF {@code fixes} must be the auto-fixer's real edit, not a description of one: only findings a
 * deterministic transform can repair carry one, and applying every emitted replacement has to
 * reproduce what {@code --fix} writes, byte for byte.
 */
class SarifFixesTest {

    private static final String SOURCE = """
            package com.example;

            import java.util.List;
            import java.util.List;
            import java.util.Map;

            public class Probe {

                public String run(boolean flag, List<String> items) {
                    if (flag == true) {
                        return items.toString();
                    }
                    return Map.of().toString();
                }
            }
            """;

    @TempDir
    Path root;

    @Test
    void emitsFixesOnlyForFindingsTheAutoFixerCanRepair() throws Exception {
        writeSource("Probe.java", SOURCE);
        AnalysisReport report = reportWith(
                violation("java:S1128", 4, "Duplicated import"),
                violation("java:S1125", 10, "Redundant comparison"),
                violation("AEM-DEAD-001", 7, "Orphaned configuration"));

        JsonNode results = results(report);

        assertEquals(3, results.size());
        List<String> withFixes = new ArrayList<>();
        for (JsonNode result : results) {
            if (result.has("fixes")) {
                withFixes.add(result.path("ruleId").asText());
            }
        }
        assertEquals(List.of("java:S1128", "java:S1125"), withFixes,
                "a rule with no deterministic transform must not carry a fix");

        JsonNode fix = results.get(0).path("fixes").get(0);
        assertTrue(fix.path("description").path("text").asText().startsWith("Fix java:S1128: "));
        JsonNode artifactChange = fix.path("artifactChanges").get(0);
        assertEquals("Probe.java", artifactChange.path("artifactLocation").path("uri").asText());
        assertEquals("%SRCROOT%", artifactChange.path("artifactLocation").path("uriBaseId").asText());
        JsonNode deletion = artifactChange.path("replacements").get(0).path("deletedRegion");
        assertEquals(4, deletion.path("startLine").asInt());
        assertEquals(5, deletion.path("endLine").asInt());
        assertEquals(1, deletion.path("endColumn").asInt());
        assertFalse(artifactChange.path("replacements").get(0).has("insertedContent"),
                "a whole-line deletion has no inserted text");
    }

    @Test
    void applyingTheEmittedReplacementsReproducesTheAutoFixOutput() throws Exception {
        writeSource("Probe.java", SOURCE);
        List<Violation> findings = List.of(
                violation("java:S1128", 4, "Duplicated import"),
                violation("java:S1125", 10, "Redundant comparison"));
        AnalysisReport report = reportWith(findings.toArray(Violation[]::new));

        String applied = applyFixes(SOURCE, results(report));

        // The same report run through the real writer, in a separate copy of the tree.
        Path fixTree = Files.createDirectories(root.resolve("fixed"));
        Files.writeString(fixTree.resolve("Probe.java"), SOURCE);
        new AutoFixEngine().fix(fixTree, findings, false);
        String written = Files.readString(fixTree.resolve("Probe.java"));

        assertEquals(written, applied, "SARIF replacements must land on exactly the --fix result");
        assertTrue(applied.contains("if (flag) {"), applied);
        assertEquals(1, countOccurrences(applied, "import java.util.List;"), applied);
    }

    @Test
    void emitsTheStandardCharsetsReplacementAndItsImportTogether() throws Exception {
        String source = """
                package com.example;

                public class Charset {

                    public byte[] encode(String value) {
                        return value.getBytes("UTF-8");
                    }
                }
                """;
        writeSource("Charset.java", source);
        List<Violation> findings = List.of(violation("java:S4719", 6, "StandardCharsets", "Charset.java"));
        AnalysisReport report = reportWith(findings.toArray(Violation[]::new));

        JsonNode result = results(report).get(0);
        JsonNode replacements = result.path("fixes").get(0)
                .path("artifactChanges").get(0).path("replacements");
        assertEquals(2, replacements.size(), "the import is part of the same repair");

        String applied = applyFixes(source, results(report));
        assertEquals("""
                package com.example;

                import java.nio.charset.StandardCharsets;

                public class Charset {

                    public byte[] encode(String value) {
                        return value.getBytes(StandardCharsets.UTF_8);
                    }
                }
                """, applied);
    }

    @Test
    void aMissingFileYieldsNoFixInsteadOfAFabricatedOne() throws Exception {
        AnalysisReport report = reportWith(violation("java:S1128", 4, "Duplicated import"));

        JsonNode results = results(report);

        assertEquals(1, results.size());
        assertFalse(results.get(0).has("fixes"));
    }

    private void writeSource(String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, content);
    }

    private AnalysisReport reportWith(Violation... violations) {
        return new AnalysisReport(root, Map.of("javaFiles", 1), List.of(violations), 1);
    }

    private static Violation violation(String ruleId, int line, String message) {
        return violation(ruleId, line, message, "Probe.java");
    }

    private static Violation violation(String ruleId, int line, String message, String filePath) {
        return new Violation(ruleId, Severity.WARNING, null, message, filePath,
                TextRegion.forLine(line), null, null, List.of());
    }

    private JsonNode results(AnalysisReport report) throws Exception {
        StringWriter out = new StringWriter();
        new SarifReportExporter().export(report, out);
        return new ObjectMapper().readTree(out.toString()).path("runs").get(0).path("results");
    }

    /** Applies every {@code fixes[]} replacement in the document, exactly as a consumer would. */
    private static String applyFixes(String content, JsonNode results) {
        record Replacement(int start, int end, String text) {
        }
        List<Replacement> replacements = new ArrayList<>();
        for (JsonNode result : results) {
            for (JsonNode fix : result.path("fixes")) {
                for (JsonNode change : fix.path("artifactChanges")) {
                    for (JsonNode replacement : change.path("replacements")) {
                        JsonNode region = replacement.path("deletedRegion");
                        int start = region.isMissingNode() ? 0
                                : offset(content, region.path("startLine").asInt(),
                                        region.path("startColumn").asInt());
                        int end = region.isMissingNode() ? 0
                                : offset(content, region.path("endLine").asInt(),
                                        region.path("endColumn").asInt());
                        String text = replacement.path("insertedContent").path("text").asText("");
                        replacements.add(new Replacement(start, end, text));
                    }
                }
            }
        }
        replacements.sort(java.util.Comparator.comparingInt(Replacement::start));
        StringBuilder applied = new StringBuilder();
        int cursor = 0;
        for (Replacement replacement : replacements) {
            applied.append(content, cursor, replacement.start());
            applied.append(replacement.text());
            cursor = replacement.end();
        }
        return applied.append(content.substring(cursor)).toString();
    }

    /** 1-based line/column to a character offset, the way a SARIF consumer resolves a region. */
    private static int offset(String content, int line, int column) {
        String[] lines = content.split("\n", -1);
        int offset = 0;
        for (int i = 0; i < line - 1; i++) {
            offset += lines[i].length() + 1;
        }
        return offset + column - 1;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
