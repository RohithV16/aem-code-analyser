package io.github.rohithv16.aem.analyser.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Completion is generated from the command model, so the flags a user can complete are the flags the
 * parser accepts. bash comes from picocli; zsh and fish are rendered from the same {@code CommandSpec}.
 */
class AnalyserCliCompletionTest {

    @TempDir
    Path tempDir;

    @Test
    void bashScriptParsesAndRegistersTheCommand() throws Exception {
        CliTestSupport.Run run = CliTestSupport.runArgs("--generate-completion", "bash");

        assertEquals(0, run.exitCode(), run.errors());
        assertTrue(run.output().contains("complete -F"), run.output());
        assertTrue(run.output().contains("aem-analyser"), run.output());
        assertTrue(run.output().contains("--fail-on"), "the flag surface must be offered");
        assertTrue(run.output().contains("WARNING"), "--fail-on values are completed");

        // The script's content is asserted above on every platform; only the shell that parses it
        // is platform-specific (windows-latest has no /bin/bash).
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "no /bin/bash on this platform");
        Path script = tempDir.resolve("aem-analyser.bash");
        Files.writeString(script, run.output());
        CliTestSupport.Run syntax = runProcess("/bin/bash", "-n", script.toString());
        assertEquals(0, syntax.exitCode(), syntax.errors());
    }

    @Test
    void zshScriptIsACompdefWithValueCandidates() throws Exception {
        CliTestSupport.Run run = CliTestSupport.runArgs("--generate-completion", "zsh");

        assertEquals(0, run.exitCode(), run.errors());
        assertTrue(run.output().startsWith("#compdef aem-analyser"), run.output());
        assertTrue(run.output().contains("--fail-on"), run.output());
        assertTrue(run.output().contains("NONE ERROR WARNING INFO"), run.output());
        assertTrue(run.output().contains("CONSOLE TEXT JSON SARIF"), run.output());
        assertTrue(run.output().contains("compdef _aem_analyser aem-analyser"), run.output());
        assertFalse(run.output().contains("has-option"), "no bash-specific helpers in a zsh script");
    }

    @Test
    void fishScriptCompletesLongAndShortNames() throws Exception {
        CliTestSupport.Run run = CliTestSupport.runArgs("--generate-completion", "fish");

        assertEquals(0, run.exitCode(), run.errors());
        assertTrue(run.output().contains("complete -c aem-analyser -s f -l format"), run.output());
        assertTrue(run.output().contains("-l fail-on"), run.output());
        assertTrue(run.output().contains("-a 'CONSOLE TEXT JSON SARIF"), run.output());
    }

    @Test
    void anUnknownShellIsAUsageErrorNotAStackTrace() {
        CliTestSupport.Run run = CliTestSupport.runArgs("--generate-completion", "tcsh");

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.errors().contains("Supported shells: bash, zsh, fish"), run.errors());
        assertTrue(run.output().isEmpty(), "no partial script on stdout");
    }

    private static CliTestSupport.Run runProcess(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bash -n must terminate");
        return new CliTestSupport.Run(process.exitValue(), stdout, stderr);
    }
}
