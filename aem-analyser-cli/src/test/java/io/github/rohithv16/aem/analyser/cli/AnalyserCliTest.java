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

    @Test
    void testIntelligentFreshnessDetection(@TempDir Path tempDir) throws java.io.IOException, InterruptedException {
        AnalyserCli cli = new AnalyserCli();
        Path core = tempDir.resolve("core");
        Path src = core.resolve("src/main/java/com/example");
        java.nio.file.Files.createDirectories(src);
        Path javaFile = src.resolve("Foo.java");
        java.nio.file.Files.writeString(javaFile, "public class Foo {}");
        java.nio.file.Files.writeString(core.resolve("pom.xml"), "<project/>");

        // 1. No coverage yet -> not fresh
        var status1 = cli.checkCoverageStatus(tempDir, java.util.List.of("core"));
        assertFalse(status1.isFresh());

        // 2. Generate coverage after source file -> fresh
        Thread.sleep(50);
        Path target = core.resolve("target");
        java.nio.file.Files.createDirectories(target);
        Path execFile = target.resolve("jacoco.exec");
        java.nio.file.Files.writeString(execFile, "mock-exec");

        var status2 = cli.checkCoverageStatus(tempDir, java.util.List.of("core"));
        assertTrue(status2.isFresh());

        // 3. Edit source file after coverage -> not fresh
        Thread.sleep(50);
        java.nio.file.Files.setLastModifiedTime(javaFile, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));

        var status3 = cli.checkCoverageStatus(tempDir, java.util.List.of("core"));
        assertFalse(status3.isFresh());
        assertTrue(status3.reason().contains("Foo.java"));
    }

    @Test
    void testSonarCliOptionsRegistered() {
        AnalyserCli cli = new AnalyserCli();
        CommandLine cmd = new CommandLine(cli);

        StringWriter sw = new StringWriter();
        cmd.usage(new PrintWriter(sw));
        String usage = sw.toString();

        assertTrue(usage.contains("--sonar"), "Usage should include --sonar option");
        assertTrue(usage.contains("--no-sonar"), "Usage should include --no-sonar option");
        assertTrue(usage.contains("--sonar-rules"), "Usage should include --sonar-rules option");

        cmd.parseArgs("--no-sonar");
        assertTrue(cli.isNoSonar(), "noSonar flag should be true when --no-sonar passed");
    }

    @Test
    void testNewEnterpriseCliOptionsRegistered() {
        AnalyserCli cli = new AnalyserCli();
        CommandLine cmd = new CommandLine(cli);

        StringWriter sw = new StringWriter();
        cmd.usage(new PrintWriter(sw));
        String usage = sw.toString();

        assertTrue(usage.contains("--changed-only"), "Usage should include --changed-only");
        assertTrue(usage.contains("--since"), "Usage should include --since");
        assertTrue(usage.contains("--fix"), "Usage should include --fix");
        assertTrue(usage.contains("--fix-dry-run"), "Usage should include --fix-dry-run");
        assertTrue(usage.contains("--fail-on"), "Usage should include --fail-on");
    }
}
