package io.github.rohithv16.aem.analyser.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AnalyserCliTest {

    @Test
    void testCollectCoverageOptionRegistered() {
        AnalyserCli cli = new AnalyserCli();
        CommandLine cmd = new CommandLine(cli);

        StringWriter sw = new StringWriter();
        cmd.usage(new PrintWriter(sw));
        String usage = sw.toString();

        assertTrue(usage.contains("--collect-coverage"), "Usage should include --collect-coverage option");
    }

    @Test
    void testResolveProjectRootDefaultsToCurrentDir(@TempDir Path tempDir) {
        AnalyserCli cli = new AnalyserCli();
        CommandLine cmd = new CommandLine(cli);
        cmd.parseArgs("--project-root", tempDir.toString(), "--collect-coverage");

        assertEquals(tempDir.toAbsolutePath().normalize(), cli.resolveProjectRoot());
    }
}
