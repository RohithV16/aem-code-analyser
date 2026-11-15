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

    @Test
    void testFindJavaTestModulesIdentifiesOnlyJavaTestModules(@TempDir Path tempDir) throws java.io.IOException {
        // Create root pom
        java.nio.file.Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        // Module 1: core (with src/test/java)
        Path core = tempDir.resolve("core");
        java.nio.file.Files.createDirectories(core.resolve("src/test/java"));
        java.nio.file.Files.writeString(core.resolve("pom.xml"), "<project/>");

        // Module 2: ui.frontend (no src/test/java)
        Path uiFrontend = tempDir.resolve("ui.frontend");
        java.nio.file.Files.createDirectories(uiFrontend.resolve("src/main"));
        java.nio.file.Files.writeString(uiFrontend.resolve("pom.xml"), "<project/>");

        AnalyserCli cli = new AnalyserCli();
        java.util.List<String> modules = cli.findJavaTestModules(tempDir);

        assertEquals(java.util.List.of("core"), modules, "Should only pick module containing src/test/java");
    }

    @Test
    void testFindExistingCoverageDiscoversExecOrCsv(@TempDir Path tempDir) throws java.io.IOException {
        AnalyserCli cli = new AnalyserCli();
        assertNull(cli.findExistingCoverage(tempDir));

        Path target = tempDir.resolve("core/target");
        java.nio.file.Files.createDirectories(target);
        Path execFile = target.resolve("jacoco.exec");
        java.nio.file.Files.writeString(execFile, "mock");

        Path found = cli.findExistingCoverage(tempDir);
        assertNotNull(found);
        assertEquals(execFile, found);
    }
}
