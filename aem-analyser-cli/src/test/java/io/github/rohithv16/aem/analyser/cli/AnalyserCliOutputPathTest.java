package io.github.rohithv16.aem.analyser.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code -o} takes a directory or a file. Naming a file must not create a directory that holds the
 * report under a default name: a pipeline that then reads the named path finds a folder.
 */
class AnalyserCliOutputPathTest {

    @Test
    void explicitSarifPathIsWrittenAsAFile(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path report = project.resolve("out/aem.sarif");

        CliTestSupport.Run run = CliTestSupport.run(project,
                "-f", "SARIF", "-o", report.toString(), "--no-html", "--no-sonar");

        assertEquals(0, run.exitCode(), run.output());
        assertTrue(Files.isRegularFile(report), "expected a SARIF file at " + report);
        assertFalse(Files.isDirectory(report), "-o with a file extension must not create a directory");
    }

    @Test
    void outputDirectoryStillReceivesTheDefaultFileName(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path directory = project.resolve("out");

        CliTestSupport.Run run = CliTestSupport.run(project,
                "-f", "SARIF", "-o", directory.toString(), "--no-html", "--no-sonar");

        assertEquals(0, run.exitCode(), run.output());
        assertTrue(Files.isRegularFile(directory.resolve("aem-analyser.sarif")),
                "a directory-style -o keeps the default file name");
    }

    /** A file path naming one format must not swallow the other reports of a multi-format run. */
    @Test
    void fileOutputPathDoesNotCollideWithOtherFormats(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path report = project.resolve("out/report.sarif");

        CliTestSupport.Run run = CliTestSupport.run(project,
                "-f", "SARIF,HTML", "-o", report.toString(), "--no-sonar");

        assertEquals(0, run.exitCode(), run.output());
        assertTrue(Files.isRegularFile(report), "SARIF must land in the named file");
        assertTrue(Files.isRegularFile(project.resolve("out/aem-analyser-report.html")),
                "the dashboard must go to the parent directory, not overwrite the SARIF file");
    }

    @Test
    void nestedDirectoryIsCreated(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path directory = project.resolve("build/reports/aem");

        CliTestSupport.Run run = CliTestSupport.run(project,
                "-f", "SONAR", "-o", directory.toString(), "--no-html", "--no-sonar");

        assertEquals(0, run.exitCode(), run.output());
        assertTrue(Files.isRegularFile(directory.resolve("sonar-aem-analyser-report.json")),
                "missing parent directories must be created");
    }
}
