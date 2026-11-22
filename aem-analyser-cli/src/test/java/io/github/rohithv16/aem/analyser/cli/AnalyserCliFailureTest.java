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
        String missing = "/tmp/aem-analyser-no-such-" + UUID.randomUUID();

        CliTestSupport.Run run = CliTestSupport.runArgs(missing, "--no-html");

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.output().contains(missing), run.output());
        assertTrue(run.output().contains("does not exist"), run.output());
        assertTrue(run.output().contains("--project-root"), run.output());
        assertFalse(run.output().contains("Analysis failed: Analysis failed:"), run.output());
        assertFalse(run.output().contains("No root pom.xml"), run.output());
    }

    @Test
    void directoryWithoutPomExplainsWhatWasExpected(@TempDir Path empty) throws Exception {
        CliTestSupport.Run run = CliTestSupport.run(empty, "--no-html");

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.output().contains("No pom.xml in " + empty.toAbsolutePath().normalize()), run.output());
        assertTrue(run.output().contains("Maven project root"), run.output());
    }

    @Test
    void brokenRulesBlockNamesTheFieldInsteadOfJacksonInternals(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Files.writeString(project.resolve(".aem-analyser.yml"), "rules: notamap\n");

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar");

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.output().contains(".aem-analyser.yml"), run.output());
        assertTrue(run.output().contains("'rules'"), run.output());
        assertTrue(run.output().contains("mapping"), run.output());
        assertFalse(run.output().contains("Cannot construct instance"), run.output());
        assertFalse(run.output().contains("LinkedHashMap"), run.output());
    }

    @Test
    void missingConfigFileNamesTheFile(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);

        CliTestSupport.Run run = CliTestSupport.runArgs("--config", project.resolve("nope.yml").toString(),
                project.toString(), "--no-html", "--no-sonar");

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.output().contains("nope.yml"), run.output());
        assertTrue(run.output().contains("not found"), run.output());
    }

    @Test
    void internalFailureExitsThreeWithoutStackFrames() {
        CliTestSupport.Run run = runPhase(false, () -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(3, run.exitCode(), run.output());
        assertTrue(run.output().contains("analyser failed during analysis"), run.output());
        assertTrue(run.output().contains("boom"), run.output());
        assertFalse(run.output().contains("\tat io.github"), run.output());
        assertFalse(run.output().contains("Exception in thread"), run.output());
    }

    @Test
    void debugPrintsTheStackTrace() {
        CliTestSupport.Run run = runPhase(true, () -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(3, run.exitCode(), run.output());
        assertTrue(run.output().contains("\tat io.github.rohithv16"), run.output());
    }

    @Test
    void analysisExceptionPrefixIsNotDoubled() {
        CliTestSupport.Run run = runPhase(false, () -> {
            throw new AnalysisException("Analysis failed: Analysis failed: No root pom.xml found under /tmp/x",
                    new IOException("unreadable"));
        });

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.output().contains("[ERROR] Analysis failed: No root pom.xml found under /tmp/x"),
                run.output());
        assertFalse(run.output().contains("Analysis failed: Analysis failed:"), run.output());
    }

    /** A wrapped runtime failure is the analyser crashing on the input, not a usage error. */
    @Test
    void analysisExceptionWithRuntimeCauseIsInternal() {
        CliTestSupport.Run run = runPhase(false, () -> {
            throw new AnalysisException("Analysis failed: index out of bounds", new IllegalStateException("x"));
        });

        assertEquals(3, run.exitCode(), run.output());
    }

    /** End-to-end exit 3: the analyser cannot do its job, which says nothing about the code. */
    @Test
    void unwritableHistoryPathExitsThreeWithoutFrames(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path historyFile = project.resolve("history-is-a-file");
        Files.writeString(historyFile, "not a directory");

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar",
                "--history", historyFile.toString());

        assertEquals(3, run.exitCode(), run.output());
        assertTrue(run.output().contains("run history"), run.output());
        assertTrue(run.output().contains("not a writable directory"), run.output());
        assertFalse(run.output().contains("\tat "), run.output());
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
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            AnalyserCli cli = new AnalyserCli();
            if (debug) {
                new CommandLine(cli).parseArgs("--debug");
            }
            int exit = cli.executePhase("analysis", phase);
            return new CliTestSupport.Run(exit, captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
        }
    }
}
