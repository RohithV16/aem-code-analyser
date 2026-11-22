package io.github.rohithv16.aem.analyser.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The process exit code is a documented contract (0 pass, 1 gate failure, 2 bad invocation, 3 the
 * analyser broke). A usage error must never look like a verdict on the code.
 */
class AnalyserCliExitCodeTest {

    @Test
    void unknownFlagIsAUsageError() {
        CommandLine cmd = new CommandLine(new AnalyserCli());
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(new StringWriter()));

        assertEquals(2, cmd.execute("--bogus-flag"),
                "an unknown flag must exit 2, not 0: a typo must not read as a passing gate");
    }

    @Test
    void unknownFormatIsAUsageError() {
        CommandLine cmd = new CommandLine(new AnalyserCli());
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(new StringWriter()));

        assertEquals(2, cmd.execute("-f", "BOGUS", "--no-html"));
    }

    @Test
    void outOfRangeOptionsAreUsageErrors() {
        CommandLine cmd = new CommandLine(new AnalyserCli());
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(new StringWriter()));

        assertEquals(2, cmd.execute("--min-coverage", "150", "--no-html"));
    }

    @Test
    void helpAndVersionStillExitZero() {
        CommandLine help = new CommandLine(new AnalyserCli());
        help.setOut(new PrintWriter(new StringWriter()));
        assertEquals(0, help.execute("--help"));

        CommandLine version = new CommandLine(new AnalyserCli());
        version.setOut(new PrintWriter(new StringWriter()));
        assertEquals(0, version.execute("--version"));
    }
}
