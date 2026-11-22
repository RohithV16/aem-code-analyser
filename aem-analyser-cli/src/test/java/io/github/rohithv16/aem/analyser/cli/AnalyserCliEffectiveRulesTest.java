package io.github.rohithv16.aem.analyser.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --list-rules} and {@code --explain} describe the run the user is about to make. Reporting the
 * registry defaults while the run honours a config (or {@code --severity}) is how a team disables a
 * rule and still sees findings.
 */
class AnalyserCliEffectiveRulesTest {

    private static final String CONFIG = "rules:\n"
            + "  AEM-DUP-010:\n"
            + "    enabled: false\n"
            + "  AEM-QUAL-003:\n"
            + "    severity: INFO\n";

    @Test
    void listRulesMarksDisabledRulesAndOverridesWithProvenance(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Files.writeString(project.resolve(".aem-analyser.yml"), CONFIG);

        CliTestSupport.Run run = CliTestSupport.run(project, "--list-rules");

        assertEquals(0, run.exitCode(), run.errors());
        String disabled = line(run.output(), "AEM-DUP-010");
        assertTrue(disabled.contains("DISABLED"), disabled);
        assertFalse(disabled.contains("WARNING"), "a disabled rule must not advertise a severity: " + disabled);
        assertTrue(disabled.contains("disabled by .aem-analyser.yml"),
                "list-rules must name the config that disabled the rule: " + disabled);

        String overridden = line(run.output(), "AEM-QUAL-003");
        assertTrue(overridden.contains("INFO*"), overridden);
        assertTrue(overridden.contains("default WARNING"),
                "an override must show what it replaced: " + overridden);

        String untouched = line(run.output(), "AEM-SEC-001");
        assertFalse(untouched.contains("*"), "a default severity must not be marked as an override: " + untouched);
    }

    @Test
    void listRulesHonoursAnExplicitConfigFile(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path config = project.resolve("cfg.yml");
        Files.writeString(config, CONFIG);

        CliTestSupport.Run run = CliTestSupport.run(project, "--list-rules", "--config", config.toString());

        assertEquals(0, run.exitCode(), run.errors());
        assertTrue(line(run.output(), "AEM-DUP-010").contains("DISABLED"),
                "--config was parsed and then ignored before:\n" + run.output());
        assertTrue(run.output().contains("cfg.yml"), run.output());
    }

    @Test
    void listRulesShowsTheCliSeverityLayer(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "--list-rules",
                "--severity", "AEM-QUAL-003=INFO");

        assertEquals(0, run.exitCode(), run.errors());
        String overridden = line(run.output(), "AEM-QUAL-003");
        assertTrue(overridden.contains("INFO*"), overridden);
        assertTrue(overridden.contains("--severity"),
                "the CLI layer must be named as the provenance: " + overridden);
    }

    @Test
    void explainReportsTheEffectiveSeverity(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Files.writeString(project.resolve(".aem-analyser.yml"), CONFIG);

        CliTestSupport.Run run = CliTestSupport.run(project, "--explain", "AEM-QUAL-003");

        assertEquals(0, run.exitCode(), run.errors());
        assertTrue(run.output().contains("Severity:     INFO (from .aem-analyser.yml; default WARNING)"),
                run.output());
    }

    @Test
    void explainReportsADisabledRule(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Files.writeString(project.resolve(".aem-analyser.yml"), CONFIG);

        CliTestSupport.Run run = CliTestSupport.run(project, "--explain", "AEM-DUP-010");

        assertEquals(0, run.exitCode(), run.errors());
        assertTrue(run.output().contains("DISABLED"), run.output());
        assertTrue(run.output().contains("Disabled by .aem-analyser.yml"), run.output());
    }

    @Test
    void listRulesFiltersByTag(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "--list-rules", "--tag", "osgi");

        assertEquals(0, run.exitCode(), run.errors());
        assertTrue(run.output().contains("tagged 'osgi'"), run.output());
        assertTrue(run.output().contains("AEM-DEAD-003C"), run.output());
        assertFalse(run.output().contains("AEM-DUP-001"),
                "a tag filter must drop rules without the tag:\n" + run.output());
    }

    @Test
    void unknownTagIsAUsageError(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "--list-rules", "--tag", "nope");

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.errors().contains("No rules tagged 'nope'"), run.errors());
        assertTrue(run.errors().contains("duplication"),
                "the error should list the tags that exist:\n" + run.errors());
    }

    @Test
    void tagWithoutListRulesIsAUsageError(@TempDir Path project) throws Exception {
        CliTestSupport.writeSchedulerProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "--tag", "osgi", "--no-html", "--no-sonar");

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.errors().contains("--tag can only be used with --list-rules"), run.errors());
    }

    @Test
    void retiredRuleStillReadsAsRetired(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "--list-rules", "--tag", "resource-lifecycle");

        assertEquals(0, run.exitCode(), run.errors());
        String retired = line(run.output(), "AEM-QUAL-006");
        assertTrue(retired.contains("RETIRED"), retired);
        assertTrue(retired.contains("AEM-QUAL-004"), retired);
    }

    private static String line(String listing, String ruleId) {
        return listing.lines()
                .filter(candidate -> candidate.contains(ruleId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(ruleId + " missing from listing:\n" + listing));
    }
}
