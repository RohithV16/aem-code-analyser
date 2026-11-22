package io.github.rohithv16.aem.analyser.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The artefact people forward has to carry the edit the tool can already make: a SARIF run over a
 * file with a duplicated import and a redundant {@code == true} yields machine-applicable fixes, and
 * applying them removes the findings.
 */
class AnalyserCliSarifFixesTest {

    private static final String PROBE = """
            package com.acme;

            import java.util.List;
            import java.util.Map;
            import java.util.List;

            public class ZzProbeFix {

                public String run(boolean flag, List<String> items) {
                    if (flag == true) {
                        return items.toString();
                    }
                    return Map.of().toString();
                }
            }
            """;

    @TempDir
    Path project;

    @Test
    void sarifCarriesApplicableFixesAndApplyingThemClearsTheFindings() throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path source = project.resolve("core/src/main/java/com/acme/ZzProbeFix.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, PROBE);

        CliTestSupport.Run run = CliTestSupport.run(project,
                "--no-html", "-f", "SARIF", "-o", project.resolve("build/report").toString());

        JsonNode results = sarifResults(project.resolve("build/report/aem-analyser.sarif"));
        List<String> fixedRules = new ArrayList<>();
        for (JsonNode result : results) {
            if (result.has("fixes")) {
                fixedRules.add(result.path("ruleId").asText());
            }
        }
        assertTrue(fixedRules.contains("java:S1128"), fixedRules.toString());
        assertTrue(fixedRules.contains("java:S1125"), fixedRules.toString());
        assertEquals(2, fixedRules.size(), "only the two machine-fixable findings carry a fix");

        String applied = applyFixes(PROBE, results);
        assertFalse(applied.contains("import java.util.List;\nimport java.util.List;"), applied);
        assertTrue(applied.contains("if (flag) {"), applied);
        Files.writeString(source, applied);

        CliTestSupport.Run after = CliTestSupport.run(project,
                "--no-html", "-f", "SARIF", "-o", project.resolve("build/after").toString());
        JsonNode afterResults = sarifResults(project.resolve("build/after/aem-analyser.sarif"));
        for (JsonNode result : afterResults) {
            assertFalse(result.path("ruleId").asText().equals("java:S1128"), "duplicate import is gone");
            assertFalse(result.path("ruleId").asText().equals("java:S1125"), "redundant comparison is gone");
            assertFalse(result.has("fixes"), "nothing left to fix: " + result);
        }
    }

    private static JsonNode sarifResults(Path sarif) throws Exception {
        return new ObjectMapper().readTree(sarif.toFile()).path("runs").get(0).path("results");
    }

    /** Applies every emitted replacement, the way a code-scanning consumer would. */
    private static String applyFixes(String content, JsonNode results) {
        record Replacement(int start, int end, String text) {
        }
        List<Replacement> replacements = new ArrayList<>();
        for (JsonNode result : results) {
            for (JsonNode fix : result.path("fixes")) {
                for (JsonNode change : fix.path("artifactChanges")) {
                    for (JsonNode replacement : change.path("replacements")) {
                        JsonNode region = replacement.path("deletedRegion");
                        replacements.add(new Replacement(
                                offset(content, region.path("startLine").asInt(),
                                        region.path("startColumn").asInt()),
                                offset(content, region.path("endLine").asInt(),
                                        region.path("endColumn").asInt()),
                                replacement.path("insertedContent").path("text").asText("")));
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

    private static int offset(String content, int line, int column) {
        String[] lines = content.split("\n", -1);
        int offset = 0;
        for (int i = 0; i < line - 1; i++) {
            offset += lines[i].length() + 1;
        }
        return offset + column - 1;
    }
}
