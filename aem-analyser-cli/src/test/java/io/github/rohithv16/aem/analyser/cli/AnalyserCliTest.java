package io.github.rohithv16.aem.analyser.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import io.github.rohithv16.aem.analyser.core.export.ReportFormat;

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

    /**
     * Running inside a module directory must analyse the whole reactor: a module-only scan hides
     * every cross-module finding (unused references, duplicated servlets, orphaned configs).
     */
    @Test
    void testResolveProjectRootClimbsFromModuleToReactor(@TempDir Path tempDir) throws java.io.IOException {
        java.nio.file.Files.writeString(tempDir.resolve("pom.xml"),
                "<project><modules><module>bundle</module></modules></project>");
        Path module = tempDir.resolve("bundle");
        java.nio.file.Files.createDirectories(module);
        java.nio.file.Files.writeString(module.resolve("pom.xml"),
                "<project><parent><groupId>com.acme</groupId><artifactId>acme.aem</artifactId>"
                        + "<version>1.0.0</version></parent><artifactId>acme.bundle</artifactId></project>");

        AnalyserCli cli = new AnalyserCli();
        new CommandLine(cli).parseArgs("--project-root", module.toString());

        assertEquals(tempDir.toAbsolutePath().normalize(), cli.resolveProjectRoot(),
                "a module directory must resolve to its reactor root");
    }

    @Test
    void testResolveProjectRootStopsAtStandaloneProject(@TempDir Path tempDir) throws java.io.IOException {
        java.nio.file.Files.writeString(tempDir.resolve("pom.xml"),
                "<project><artifactId>standalone</artifactId></project>");

        AnalyserCli cli = new AnalyserCli();
        new CommandLine(cli).parseArgs("--project-root", tempDir.toString());

        assertEquals(tempDir.toAbsolutePath().normalize(), cli.resolveProjectRoot());
    }

    /**
     * An empty {@code <relativePath/>} means the parent is resolved from a repository, so the
     * directory is not a module of the POM sitting above it and the walk must stop.
     */
    @Test
    void testResolveProjectRootDoesNotClimbPastExternalParent(@TempDir Path tempDir) throws java.io.IOException {
        java.nio.file.Files.writeString(tempDir.resolve("pom.xml"), "<project><artifactId>host</artifactId></project>");
        Path module = tempDir.resolve("external-parent-child");
        java.nio.file.Files.createDirectories(module);
        java.nio.file.Files.writeString(module.resolve("pom.xml"),
                "<project><parent><groupId>org.example</groupId><artifactId>corp-parent</artifactId>"
                        + "<version>9</version><relativePath/></parent><artifactId>child</artifactId></project>");

        AnalyserCli cli = new AnalyserCli();
        new CommandLine(cli).parseArgs("--project-root", module.toString());

        assertEquals(module.toAbsolutePath().normalize(), cli.resolveProjectRoot(),
                "an externally-parented POM is its own root");
    }

    @Test
    void testPositionalArgumentSetsProjectRoot(@TempDir Path tempDir) throws java.io.IOException {
        java.nio.file.Files.writeString(tempDir.resolve("pom.xml"),
                "<project><artifactId>positional</artifactId></project>");

        AnalyserCli cli = new AnalyserCli();
        new CommandLine(cli).parseArgs(tempDir.toString());

        assertEquals(tempDir.toAbsolutePath().normalize(), cli.resolveProjectRoot(),
                "the positional PROJECT_ROOT must behave like --project-root");
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
        assertFalse(cli.isSonar(), "--no-sonar must disable SonarJava");
    }

    /**
     * Regression: {@code --sonar} used to set the flag to false (picocli assigns the opposite of
     * defaultValue for zero-arity booleans), silently dropping every SonarJava finding.
     */
    @Test
    void testExplicitSonarFlagKeepsSonarEnabled() {
        AnalyserCli cli = new AnalyserCli();
        new CommandLine(cli).parseArgs("--sonar");
        assertTrue(cli.isSonar(), "--sonar must keep SonarJava enabled");
        assertFalse(cli.isNoSonar(), "--sonar must not look like --no-sonar");
    }

    @Test
    void testSonarEnabledByDefault() {
        AnalyserCli cli = new AnalyserCli();
        new CommandLine(cli).parseArgs("--no-html");
        assertTrue(cli.isSonar(), "SonarJava is on by default");
    }

    /** Regression: duplicate format entries must not double-write or double-print. */
    @Test
    void testDuplicateFormatsAreCollapsed() {
        AnalyserCli cli = new AnalyserCli();
        new CommandLine(cli).parseArgs("-f", "CONSOLE,console,SARIF");
        assertEquals(2, cli.parseFormats().size(), "duplicate formats must be collapsed");
    }

    /** Regression: --no-html must win even when HTML is requested via -f HTML. */
    @Test
    void testNoHtmlOverridesExplicitHtmlFormat() {
        AnalyserCli cli = new AnalyserCli();
        new CommandLine(cli).parseArgs("-f", "HTML", "--no-html");
        assertFalse(cli.parseFormats().contains(ReportFormat.HTML), "--no-html must remove HTML");
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
        assertTrue(usage.contains("--baseline"), "Usage should include --baseline");
        assertTrue(usage.contains("--update-baseline"), "Usage should include --update-baseline");
        assertTrue(usage.contains("--dependency-jars"), "Usage should include --dependency-jars");
        assertTrue(usage.contains("--ignore-config"), "Usage should include --ignore-config");
        assertTrue(usage.contains("--list-rules"), "Usage should include --list-rules");
        assertTrue(usage.contains("--explain"), "Usage should include --explain");
        assertTrue(usage.contains("--no-html"), "Usage should include --no-html");
    }

    @Test
    void executeReturnsTwoForInvalidFormat() {
        AnalyserCli cli = new AnalyserCli();
        new CommandLine(cli).parseArgs("--format", "BOGUS");
        assertEquals(2, cli.execute());
    }

    @Test
    void executeReturnsZeroForRuleExplain() {
        AnalyserCli cli = new AnalyserCli();
        new CommandLine(cli).parseArgs("--explain", "AEM-DEAD-001");
        assertEquals(0, cli.execute());
    }

    @Test
    void listsAndExplainsRules() {
        AnalyserCli cli = new AnalyserCli();

        StringWriter listOut = new StringWriter();
        cli.printRules(new PrintWriter(listOut));
        assertTrue(listOut.toString().contains("AEM-DEAD-001"), listOut.toString());

        StringWriter explainOut = new StringWriter();
        assertEquals(0, cli.printExplain(new PrintWriter(explainOut), "AEM-DEAD-002A"));
        assertTrue(explainOut.toString().contains("GhostConfigurationProperty"), explainOut.toString());
        assertTrue(explainOut.toString().contains("Remediation:"), explainOut.toString());

        StringWriter unknownOut = new StringWriter();
        assertEquals(2, cli.printExplain(new PrintWriter(unknownOut), "AEM-NOPE-999"));
        assertTrue(unknownOut.toString().contains("Unknown rule"), unknownOut.toString());
    }

    @Test
    void rejectsOutOfRangeNumericOptions() {
        AnalyserCli coverage = new AnalyserCli();
        new CommandLine(coverage).parseArgs("--min-coverage", "150");
        assertThrows(IllegalArgumentException.class, coverage::validateNumericOptions);

        AnalyserCli threads = new AnalyserCli();
        new CommandLine(threads).parseArgs("--threads", "0");
        assertThrows(IllegalArgumentException.class, threads::validateNumericOptions);

        AnalyserCli duplication = new AnalyserCli();
        new CommandLine(duplication).parseArgs("--max-duplication", "-1");
        assertThrows(IllegalArgumentException.class, duplication::validateNumericOptions);
    }
}
