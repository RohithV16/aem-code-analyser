package io.github.rohithv16.aem.analyser.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * stdout is a report channel: {@code aem-analyser … > report.txt} must leave a file that parses as a
 * report, while the terminal still shows progress. {@code -q} turns the progress channel off.
 */
class AnalyserCliStdoutPurityTest {

    @Test
    void stdoutCarriesOnlyTheReportAndProgressGoesToStderr(@TempDir Path project) throws Exception {
        CliTestSupport.writeSchedulerProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "-f", "CONSOLE,SARIF", "--no-html",
                "--no-sonar", "-o", project.resolve("out").toString());

        assertEquals(0, run.exitCode(), run.errors());
        assertTrue(run.output().contains("AEM CODE ANALYSER"), run.output());
        assertTrue(run.output().contains("Total Violations: 1"), run.output());

        assertFalse(run.output().contains("written to"),
                "the report file must not carry the 'written to' notice:\n" + run.output());
        assertTrue(run.errors().contains("SARIF report written to"),
                "the SARIF notice belongs on stderr:\n" + run.errors());
    }

    @Test
    void noStdoutLineLooksLikeAProgressLine(@TempDir Path project) throws Exception {
        CliTestSupport.writeSchedulerProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "-f", "CONSOLE,SARIF", "--no-html",
                "--no-sonar", "--verbose", "-o", project.resolve("out").toString());

        // The console report's own severity lines are "[WARNING] AEM-QUAL-003: …"; every other
        // flush-left bracketed line is progress chatter ("[INFO] …", "[WARN] …") and belongs on
        // stderr.
        String offending = run.output().lines()
                .filter(line -> line.startsWith("["))
                .filter(line -> !line.matches("\\[(ERROR|WARNING|INFO)\\] AEM-[A-Z0-9-]+:.*"))
                .findFirst()
                .orElse(null);
        assertTrue(offending == null,
                "stdout must contain only the report, found progress line: " + offending
                        + "\n--- stdout ---\n" + run.output()
                        + "\n--- stderr ---\n" + run.errors());
        assertFalse(run.output().contains("Analysis diagnostics"),
                "--verbose diagnostics belong on stderr:\n" + run.output());
        assertTrue(run.errors().contains("[INFO] Analysis diagnostics")
                        || run.errors().contains("SARIF report written to"),
                "progress must be visible on stderr:\n" + run.errors());
    }

    @Test
    void quietSilencesStderrButKeepsTheReport(@TempDir Path project) throws Exception {
        CliTestSupport.writeSchedulerProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "-f", "CONSOLE,SARIF", "--no-html",
                "--no-sonar", "-q", "-o", project.resolve("out").toString());

        assertEquals(0, run.exitCode(), run.errors());
        assertEquals("", run.errors(), "-q must silence the progress channel");
        assertTrue(run.output().contains("Total Violations: 1"),
                "quiet mode must not touch the report:\n" + run.output());
    }

    @Test
    void quietDoesNotHideErrors(@TempDir Path project) throws Exception {
        CliTestSupport.writeSchedulerProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "-q", "--no-html", "--no-sonar",
                "-f", "BOGUS");

        assertEquals(2, run.exitCode());
        assertTrue(run.errors().contains("[ERROR] Unknown report format 'BOGUS'"),
                "a usage error must survive -q:\n" + run.errors());
    }

    @Test
    void usageListsTheQuietFlag() {
        String usage = new picocli.CommandLine(new AnalyserCli()).getUsageMessage();
        assertTrue(usage.contains("-q, --quiet"), usage);
    }
}
