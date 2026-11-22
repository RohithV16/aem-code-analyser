package io.github.rohithv16.aem.analyser.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --severity} is the per-run policy layer: it must change the report and the gate, override the
 * committed config instead of the other way round, and refuse a rule id nobody produces.
 */
class AnalyserCliSeverityOverrideTest {

    @Test
    void overrideDowngradesSeverityAndChangesTheCounts(@TempDir Path project) throws Exception {
        CliTestSupport.writeSchedulerProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "-f", "CONSOLE", "--no-html", "--no-sonar",
                "--severity", "AEM-QUAL-003=INFO");

        assertEquals(0, run.exitCode(), run.errors());
        assertTrue(run.output().contains("Total Violations: 1 (0 Errors, 0 Warnings, 1 Info)"),
                run.output());
        assertTrue(run.output().contains("[INFO] AEM-QUAL-003"),
                "the finding must render at the overridden severity:\n" + run.output());
        assertFalse(run.output().contains("[WARNING] AEM-QUAL-003"), run.output());
    }

    @Test
    void offDisablesTheRuleForOneRun(@TempDir Path project) throws Exception {
        CliTestSupport.writeSchedulerProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "-f", "CONSOLE", "--no-html", "--no-sonar",
                "--severity", "AEM-QUAL-003=OFF");

        assertEquals(0, run.exitCode(), run.errors());
        assertTrue(run.output().contains("Total Violations: 0 (0 Errors, 0 Warnings, 0 Infos)"),
                run.output());
        assertFalse(run.output().contains("AEM-QUAL-003"), run.output());
    }

    @Test
    void cliWinsOverTheConfigFile(@TempDir Path project) throws Exception {
        CliTestSupport.writeSchedulerProject(project);
        // Committed policy escalates the rule to ERROR, which fails the gate.
        Files.writeString(project.resolve(".aem-analyser.yml"),
                "rules:\n  AEM-QUAL-003:\n    severity: ERROR\n");

        CliTestSupport.Run fromConfig = CliTestSupport.run(project, "-f", "CONSOLE", "--no-html",
                "--no-sonar");
        assertEquals(1, fromConfig.exitCode(),
                "the committed config must still gate:\n" + fromConfig.output());
        assertTrue(fromConfig.output().contains("1 Error"), fromConfig.output());

        CliTestSupport.Run fromCli = CliTestSupport.run(project, "-f", "CONSOLE", "--no-html",
                "--no-sonar", "--severity", "AEM-QUAL-003=INFO");
        assertEquals(0, fromCli.exitCode(),
                "--severity must win over .aem-analyser.yml:\n" + fromCli.output());
        assertTrue(fromCli.output().contains("Total Violations: 1 (0 Errors, 0 Warnings, 1 Info)"),
                fromCli.output());
    }

    @Test
    void optionIsRepeatable(@TempDir Path project) throws Exception {
        CliTestSupport.writeSchedulerProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "-f", "CONSOLE", "--no-html", "--no-sonar",
                "--severity", "AEM-QUAL-003=INFO", "--severity", "AEM-DUP-010=OFF");

        assertEquals(0, run.exitCode(), run.errors());
        assertTrue(run.output().contains("1 Info"), run.output());
    }

    @Test
    void unknownRuleIdIsAUsageError(@TempDir Path project) throws Exception {
        CliTestSupport.writeSchedulerProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar",
                "--severity", "AEM-NOPE-999=INFO");

        assertEquals(2, run.exitCode(),
                "a typo must not run as a silently ignored policy:\n" + run.output());
        assertTrue(run.errors().contains("Unknown rule 'AEM-NOPE-999'"), run.errors());
        assertTrue(run.errors().contains("--list-rules"),
                "the error must point at the catalogue:\n" + run.errors());
    }

    @Test
    void unknownSeverityValueIsAUsageError(@TempDir Path project) throws Exception {
        CliTestSupport.writeSchedulerProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar",
                "--severity", "AEM-QUAL-003=LOUD");

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.errors().contains("Unknown severity 'LOUD' for rule AEM-QUAL-003"),
                run.errors());
        assertTrue(run.errors().contains("OFF"),
                "OFF is a supported value and must be advertised:\n" + run.errors());
    }

    @Test
    void malformedSpecIsAUsageError(@TempDir Path project) throws Exception {
        CliTestSupport.writeSchedulerProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar",
                "--severity", "AEM-QUAL-003");

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.errors().contains("Expected RULE=SEVERITY"), run.errors());
    }
}
