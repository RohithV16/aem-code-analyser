package io.github.rohithv16.aem.analyser.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console report is the product for a developer: the verdict, the counts and the files written
 * come first, and {@code --group-by} changes how the findings are listed.
 */
class AnalyserCliConsoleReportTest {

    @Test
    void triageBlockLeadsWithVerdictCountsAndReportPaths(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path output = project.resolve("out");

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-color", "--no-sonar", "--no-html",
                "-f", "CONSOLE,SARIF", "-o", output.toString());

        assertEquals(0, run.exitCode(), run.output());
        String[] lines = run.output().split("\n", -1);
        assertTrue(lines[1].contains("AEM CODE ANALYSER"), run.output());
        assertTrue(lines[1].contains("GATE PASSED"), run.output());
        assertTrue(run.output().contains("Total Violations: 0"), run.output());
        assertTrue(run.output().contains("Reports: "), run.output());
        assertTrue(run.output().indexOf("Reports: ") < run.output().indexOf("FINDINGS ("),
                "the written report paths must precede the findings:\n" + run.output());
    }

    @Test
    void unknownGroupByIsAUsageErrorNamingTheAcceptedValues(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "--group-by", "bogus", "--no-html");

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.output().contains("--group-by"), run.output());
        assertTrue(run.output().contains("FILE, RULE, NONE"), run.output());
    }

    @Test
    void groupByFileIsAcceptedAndAnnounced(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-sonar", "--no-html",
                "--group-by", "file");

        assertEquals(0, run.exitCode(), run.output());
        assertTrue(run.output().contains("grouped by file"), run.output());
    }
}
