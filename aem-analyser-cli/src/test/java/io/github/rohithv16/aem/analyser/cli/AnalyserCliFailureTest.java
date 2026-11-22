package io.github.rohithv16.aem.analyser.cli;

import io.github.rohithv16.aem.analyser.core.api.AnalysisException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failure message has to name the input, the expectation and the fix, and the exit code has to say
 * which kind of failure it was: 2 for a wrong invocation, 3 when the analyser itself broke, 1 for a
 * failed gate. A raw Jackson chain or a doubled prefix is a bug, not a diagnostic.
 */
class AnalyserCliFailureTest {

    @Test
    void missingProjectRootNamesThePathAndExitsTwo() throws Exception {
        // Portable: /tmp is D:\tmp on Windows, so assert on the unique leaf name, not a POSIX path.
        String name = "aem-analyser-no-such-" + UUID.randomUUID();
        String missing = java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).resolve(name).toString();

        CliTestSupport.Run run = CliTestSupport.runArgs(missing, "--no-html");

        assertEquals(2, run.exitCode(), text(run));
        assertTrue(text(run).contains(name), text(run));
        assertTrue(text(run).contains("does not exist"), text(run));
        assertTrue(text(run).contains("--project-root"), text(run));
        assertFalse(text(run).contains("Analysis failed: Analysis failed:"), text(run));
        assertFalse(text(run).contains("No root pom.xml"), text(run));
    }

    @Test
    void directoryWithoutPomExplainsWhatWasExpected(@TempDir Path empty) throws Exception {
        CliTestSupport.Run run = CliTestSupport.run(empty, "--no-html");

        assertEquals(2, run.exitCode(), text(run));
        assertTrue(text(run).contains("No pom.xml in " + empty.toAbsolutePath().normalize()), text(run));
        assertTrue(text(run).contains("Maven project root"), text(run));
    }

    @Test
    void brokenRulesBlockNamesTheFieldInsteadOfJacksonInternals(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Files.writeString(project.resolve(".aem-analyser.yml"), "rules: notamap\n");

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar");

        assertEquals(2, run.exitCode(), text(run));
        assertTrue(text(run).contains(".aem-analyser.yml"), text(run));
        assertTrue(text(run).contains("'rules'"), text(run));
        assertTrue(text(run).contains("mapping"), text(run));
        assertFalse(text(run).contains("Cannot construct instance"), text(run));
        assertFalse(text(run).contains("LinkedHashMap"), text(run));
    }

    @Test
    void missingConfigFileNamesTheFile(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);

        CliTestSupport.Run run = CliTestSupport.runArgs("--config", project.resolve("nope.yml").toString(),
                project.toString(), "--no-html", "--no-sonar");

        assertEquals(2, run.exitCode(), text(run));
        assertTrue(text(run).contains("nope.yml"), text(run));
        assertTrue(text(run).contains("not found"), text(run));
    }

    @Test
    void internalFailureExitsThreeWithoutStackFrames() {
        CliTestSupport.Run run = runPhase(false, () -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(3, run.exitCode(), text(run));
        assertTrue(text(run).contains("analyser failed during analysis"), text(run));
        assertTrue(text(run).contains("boom"), text(run));
        assertFalse(text(run).contains("\tat io.github"), text(run));
        assertFalse(text(run).contains("Exception in thread"), text(run));
    }

    @Test
    void debugPrintsTheStackTrace() {
        CliTestSupport.Run run = runPhase(true, () -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(3, run.exitCode(), text(run));
        assertTrue(text(run).contains("\tat io.github.rohithv16"), text(run));
    }

    @Test
    void analysisExceptionPrefixIsNotDoubled() {
        CliTestSupport.Run run = runPhase(false, () -> {
            throw new AnalysisException("Analysis failed: Analysis failed: No root pom.xml found under /tmp/x",
                    new IOException("unreadable"));
        });

        assertEquals(2, run.exitCode(), text(run));
        assertTrue(text(run).contains("[ERROR] Analysis failed: No root pom.xml found under /tmp/x"),
                text(run));
        assertFalse(text(run).contains("Analysis failed: Analysis failed:"), text(run));
    }

    /** A wrapped runtime failure is the analyser crashing on the input, not a usage error. */
    @Test
    void analysisExceptionWithRuntimeCauseIsInternal() {
        CliTestSupport.Run run = runPhase(false, () -> {
            throw new AnalysisException("Analysis failed: index out of bounds", new IllegalStateException("x"));
        });

        assertEquals(3, run.exitCode(), text(run));
    }

    /** End-to-end exit 3: the analyser cannot do its job, which says nothing about the code. */
    @Test
    void unwritableHistoryPathExitsThreeWithoutFrames(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path historyFile = project.resolve("history-is-a-file");
        Files.writeString(historyFile, "not a directory");

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar",
                "--history", historyFile.toString());

        assertEquals(3, run.exitCode(), text(run));
        assertTrue(text(run).contains("run history"), text(run));
        assertTrue(text(run).contains("not a writable directory"), text(run));
        assertFalse(text(run).contains("\tat "), text(run));
    }

    /** The picocli handler is the last line of defence: it must not report a crash as exit 1. */
    @Test
    void uncaughtExecutionExceptionExitsThree() {
        CommandLine commandLine = new CommandLine(new FaultyCommand());
        AnalyserCli.installExceptionHandlers(commandLine);
        StringWriter err = new StringWriter();
        commandLine.setErr(new PrintWriter(err, true));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        assertEquals(3, commandLine.execute());
        assertTrue(err.toString().contains("The analyser failed: boom"), err.toString());
        assertFalse(err.toString().contains("\tat io.github"), err.toString());
    }

    /** A command that crashes, so the picocli handler is exercised through a real execution. */
    @CommandLine.Command(name = "faulty")
    static final class FaultyCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            throw new IllegalStateException("boom");
        }
    }

    private static CliTestSupport.Run runPhase(boolean debug, AnalyserCli.Phase phase) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(capturedOut, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(capturedErr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            AnalyserCli cli = new AnalyserCli();
            if (debug) {
                new CommandLine(cli).parseArgs("--debug");
            }
            int exit = cli.executePhase("analysis", phase);
            return new CliTestSupport.Run(exit,
                    capturedOut.toString(StandardCharsets.UTF_8),
                    capturedErr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /** Failure text lands on stderr once stdout is report-only, so assertions read both channels. */
    private static String text(CliTestSupport.Run run) {
        return run.output() + run.errors();
    }
}
