package io.github.rohithv16.aem.analyser.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --report-epoch} installs a fixed clock and drops the wall-clock duration, so the same tree
 * yields identical report bytes and a committed dashboard only changes when the findings do.
 */
class AnalyserCliReproducibleReportTest {

    @Test
    void twoRunsWithTheSameEpochProduceIdenticalHtml(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path first = project.resolve("first.html");
        Path second = project.resolve("second.html");

        CliTestSupport.Run firstRun = CliTestSupport.run(project, "--no-sonar", "-f", "HTML",
                "-o", first.toString(), "--report-epoch", "1700000000");
        assertEquals(0, firstRun.exitCode(), firstRun.output());
        Thread.sleep(1100);
        CliTestSupport.Run secondRun = CliTestSupport.run(project, "--no-sonar", "-f", "HTML",
                "-o", second.toString(), "--report-epoch", "1700000000");
        assertEquals(0, secondRun.exitCode(), secondRun.output());

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second),
                "two runs of an unchanged tree with a fixed epoch must be byte-identical");
        String html = Files.readString(first, StandardCharsets.UTF_8);
        assertTrue(html.contains("Generated 2023-11-14 22:13:20 UTC"), html);
        assertFalse(html.contains("<span>Analysis "), "the duration must be omitted: " + html);
    }

    /** Without the flag the report is a live document: the timestamp and duration change. */
    @Test
    void withoutAnEpochTwoRunsDiffer(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path first = project.resolve("live-1.html");
        Path second = project.resolve("live-2.html");

        CliTestSupport.run(project, "--no-sonar", "-f", "HTML", "-o", first.toString());
        Thread.sleep(1100);
        CliTestSupport.run(project, "--no-sonar", "-f", "HTML", "-o", second.toString());

        assertNotEquals(Files.readString(first, StandardCharsets.UTF_8),
                Files.readString(second, StandardCharsets.UTF_8));
    }

    @Test
    void consoleOmitsTheDurationUnderAFixedEpoch(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);

        CliTestSupport.Run live = CliTestSupport.run(project, "--no-html", "--no-sonar", "--no-color");
        CliTestSupport.Run fixed = CliTestSupport.run(project, "--no-html", "--no-sonar", "--no-color",
                "--report-epoch", "1700000000");

        assertTrue(live.output().contains(" s \u00b7"), live.output());
        assertFalse(fixed.output().contains(" s \u00b7"), fixed.output());
        assertTrue(fixed.output().contains("Total Violations: 0"), fixed.output());
    }

    @Test
    void invalidReportEpochIsAUsageError(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar",
                "--report-epoch", "not-a-timestamp");

        assertEquals(2, run.exitCode(), run.output());
        assertTrue((run.output() + run.errors()).contains("--report-epoch"), run.output());
        assertTrue((run.output() + run.errors()).contains("whole seconds"), run.output());
    }

    @Test
    void negativeReportEpochIsAUsageError(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar",
                "--report-epoch=-5");

        assertEquals(2, run.exitCode(), run.output());
        assertTrue((run.output() + run.errors()).contains("0 or greater"), run.output());
    }
}
