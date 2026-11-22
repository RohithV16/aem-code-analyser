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

        assertTrue(run.output().contains("references retired rule 'AEM-QUAL-006'"),
                "a config entry for a retired rule must warn, not resolve silently:\n" + run.output());
        assertTrue(run.output().contains("AEM-QUAL-004"), run.output());
    }

    @Test
    void baselineReferencingRetiredRuleWarns(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Files.writeString(project.resolve(".aem-analyser-baseline.json"),
                "{\"schemaVersion\":1,\"toolVersion\":\"test\",\"entries\":["
                        + "{\"fingerprint\":\"abc\",\"ruleId\":\"AEM-QUAL-006\","
                        + "\"filePath\":\"core/Service.java\",\"subtype\":null}]}");

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar");

        assertTrue(run.output().contains("Baseline .aem-analyser-baseline.json references retired rule"),
                run.output());
    }
}
