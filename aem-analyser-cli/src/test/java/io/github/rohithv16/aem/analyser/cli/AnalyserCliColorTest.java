package io.github.rohithv16.aem.analyser.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Colour is presentation, and it corrupts every machine consumer of the console report: a redirected
 * report must be plain text. Colour is therefore on only for a terminal.
 */
class AnalyserCliColorTest {

    @Test
    void colourIsDisabledWhenStdoutIsNotATerminal() {
        AnalyserCli cli = new AnalyserCli();
        new CommandLine(cli).parseArgs("--no-html");

        assertFalse(cli.consoleColorEnabled(),
                "a test runner has no TTY, so the default must be plain text");
    }

    @Test
    void noColorFlagAlwaysWins() {
        AnalyserCli cli = new AnalyserCli();
        new CommandLine(cli).parseArgs("--no-html", "--no-color");

        assertFalse(cli.consoleColorEnabled());
    }

    /** The end-to-end promise: a redirected CONSOLE report carries zero escape sequences. */
    @Test
    void redirectedReportContainsNoAnsiEscapes(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path report = project.resolve("report.txt");

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar");
        Files.writeString(report, run.output());

        assertEquals(0, run.exitCode(), run.output());
        assertFalse(run.output().contains("\u001B"), "console output must not contain ANSI escapes");
        assertTrue(run.output().contains("AEM CODE ANALYSER"), run.output());
    }
}
