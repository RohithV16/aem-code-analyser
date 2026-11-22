package io.github.rohithv16.aem.analyser.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retired rules have no producer. Presenting one as an active rule lets a team "disable" a check that
 * never ran and believe the gate covers it.
 */
class AnalyserCliRetiredRulesTest {

    @Test
    void listRulesMarksRetiredRule() {
        StringWriter listOut = new StringWriter();
        new AnalyserCli().printRules(new PrintWriter(listOut));
        String listing = listOut.toString();

        String retiredLine = listing.lines()
                .filter(line -> line.contains("AEM-QUAL-006"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AEM-QUAL-006 missing from --list-rules:\n" + listing));

        assertTrue(retiredLine.contains("RETIRED"), retiredLine);
        assertTrue(retiredLine.contains("AEM-QUAL-004"),
                "the replacement id must be named: " + retiredLine);
        assertFalse(retiredLine.contains("WARNING"),
                "a retired rule must not advertise a severity it can no longer produce: " + retiredLine);
    }

    @Test
    void listRulesStillShowsLiveSeverities() {
        StringWriter listOut = new StringWriter();
        new AnalyserCli().printRules(new PrintWriter(listOut));

        String liveLine = listOut.toString().lines()
                .filter(line -> line.contains("AEM-QUAL-004"))
                .findFirst()
                .orElseThrow();
        assertTrue(liveLine.contains("ERROR"), liveLine);
        assertFalse(liveLine.contains("RETIRED"), liveLine);
    }

    @Test
    void explainNamesRetirementAndReplacement() {
        StringWriter explainOut = new StringWriter();
        assertEquals(0, new AnalyserCli().printExplain(new PrintWriter(explainOut), "AEM-QUAL-006"));
        String explain = explainOut.toString();

        assertTrue(explain.contains("RETIRED"), explain);
        assertTrue(explain.contains("replaced by AEM-QUAL-004"), explain);
        assertTrue(explain.contains("no effect"), "the explain output must say the id is inert: " + explain);
    }

    @Test
    void configReferencingRetiredRuleWarns(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Files.writeString(project.resolve(".aem-analyser.yml"),
                "rules:\n  AEM-QUAL-006:\n    enabled: false\n");

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar");

        assertTrue(run.errors().contains("AEM-QUAL-006 is retired; findings now come from AEM-QUAL-004"),
                "a config entry for a retired rule must warn, not resolve silently:\n" + run.errors());
        assertEquals(1, run.errors().lines().filter(line -> line.contains("[WARNING]")).count(),
                "one warning per retired id, not a second line telling the user to run --list-rules:\n"
                        + run.errors());
    }

    @Test
    void listRulesJsonExposesLifecycleState() throws Exception {
        CliTestSupport.Run run = CliTestSupport.runArgs("--list-rules", "--format", "json");

        assertEquals(0, run.exitCode(), run.errors());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(run.output());
        com.fasterxml.jackson.databind.JsonNode retired = null;
        for (var row : json) {
            if ("AEM-QUAL-006".equals(row.path("id").asText())) {
                retired = row;
            }
        }
        assertTrue(retired != null, "--list-rules --format json must include every registry id:\n" + run.output());
        assertEquals("retired", retired.path("status").asText());
        assertEquals("AEM-QUAL-004", retired.path("replacedBy").asText());
        assertEquals("1.0.0", retired.path("since").asText());
        assertFalse(retired.path("enabled").asBoolean(), "a retired id cannot be enabled");
    }

    @Test
    void listRulesJsonCarriesProbeEvidenceForEveryActiveRule() throws Exception {
        CliTestSupport.Run run = CliTestSupport.runArgs("--list-rules", "--format", "json");

        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(run.output());
        int withoutTwin = 0;
        for (var row : json) {
            if (row.path("negativeProbe").isNull()) {
                withoutTwin++;
                assertEquals("AEM-QUAL-006", row.path("id").asText(),
                        "only the retired id may lack a clean twin");
            } else {
                assertFalse(row.path("positiveProbe").asText().isBlank());
                assertTrue(row.path("positiveLine").asInt() > 0);
            }
        }
        assertEquals(1, withoutTwin,
                "--list-rules --format json must expose one two-sided row per active rule");
    }

    @Test
    void baselineReferencingRetiredRuleWarns(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Files.writeString(project.resolve(".aem-analyser-baseline.json"),
                "{\"schemaVersion\":" + io.github.rohithv16.aem.analyser.core.baseline.Baseline.CURRENT_SCHEMA_VERSION
                        + ",\"toolVersion\":\"test\",\"entries\":["
                        + "{\"fingerprint\":\"abc\",\"ruleId\":\"AEM-QUAL-006\","
                        + "\"filePath\":\"core/Service.java\",\"subtype\":null}]}");

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar");

        assertTrue(run.errors().contains("Baseline .aem-analyser-baseline.json: AEM-QUAL-006 is retired"),
                run.errors());
    }
}
