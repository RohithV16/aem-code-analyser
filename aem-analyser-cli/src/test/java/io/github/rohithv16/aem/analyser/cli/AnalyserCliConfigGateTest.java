package io.github.rohithv16.aem.analyser.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The committed {@code gate:} block is what makes {@code aem-analyser .} with no flags fail exactly
 * the way the pipeline does. A command-line flag still wins for a one-off run, and the gate never
 * changes a finding — only the verdict, the formats and the output location.
 */
class AnalyserCliConfigGateTest {

    @TempDir
    Path project;

    private void writeConfig(String yaml) throws IOException {
        Files.writeString(project.resolve(".aem-analyser.yml"), yaml);
    }

    @Test
    void gateFailOnWarningFailsTheRunWithoutAnyFlag() throws Exception {
        CliTestSupport.writeSchedulerProject(project);
        writeConfig("""
                schemaVersion: 1
                gate:
                  failOn: WARNING
                """);

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar");

        assertEquals(1, run.exitCode(), run.output());
        assertTrue(run.output().contains("GATE FAILED"), run.output());
        assertTrue(run.errors().contains("Quality gate from .aem-analyser.yml: failOn=WARNING"),
                run.errors());
    }

    @Test
    void theFlagBeatsTheCommittedGate() throws Exception {
        CliTestSupport.writeSchedulerProject(project);
        writeConfig("""
                gate:
                  failOn: WARNING
                """);

        CliTestSupport.Run run = CliTestSupport.run(project,
                "--no-html", "--no-sonar", "--fail-on", "NONE");

        assertEquals(0, run.exitCode(), run.output());
        assertTrue(run.output().contains("GATE NOT ENFORCED"), run.output());
        assertFalse(run.output().contains("GATE FAILED"), run.output());
    }

    @Test
    void theFlagBeatsACommittedThreshold() throws Exception {
        CliTestSupport.writeSchedulerProject(project);
        writeConfig("""
                gate:
                  maxWarnings: 0
                """);

        CliTestSupport.Run gated = CliTestSupport.run(project, "--no-html", "--no-sonar");
        assertEquals(1, gated.exitCode(), gated.output());
        assertTrue(gated.output().contains("exceed --max-warnings 0"), gated.output());

        CliTestSupport.Run overridden = CliTestSupport.run(project,
                "--no-html", "--no-sonar", "--max-warnings", "50");
        assertEquals(0, overridden.exitCode(), overridden.output());
    }

    @Test
    void committedFormatsAndOutputApplyOnlyWhenTheFlagsAreAbsent() throws Exception {
        CliTestSupport.writeSchedulerProject(project);
        Path out = project.resolve("build/reports");
        writeConfig("gate:\n  formats: [JSON]\n  output: " + out + "\n");

        CliTestSupport.Run defaulted = CliTestSupport.run(project, "--no-html", "--no-sonar");
        assertEquals(0, defaulted.exitCode(), defaulted.output() + defaulted.errors());
        assertTrue(Files.isRegularFile(out.resolve("aem-analyser-report.json")),
                "gate.formats/gate.output must choose the report when no flag does: " + defaulted.errors());
        assertFalse(defaulted.output().contains("AEM CODE ANALYSER"),
                "the console renderer was replaced by the committed JSON format");

        CliTestSupport.Run explicit = CliTestSupport.run(project,
                "--no-html", "--no-sonar", "-f", "CONSOLE", "-o", project.resolve("build/console").toString());
        assertEquals(0, explicit.exitCode(), explicit.output());
        assertTrue(explicit.output().contains("AEM CODE ANALYSER"), explicit.output());
    }

    @Test
    void anUnknownGateFailOnIsAUsageErrorNamingTheField() throws Exception {
        CliTestSupport.writeSchedulerProject(project);
        writeConfig("""
                gate:
                  failOn: SOMETIMES
                """);

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar");

        assertEquals(2, run.exitCode(), run.output());
        assertTrue(run.errors().contains("gate.failOn"), run.errors());
        assertTrue(run.errors().contains("SOMETIMES"), run.errors());
    }

    @Test
    void theGateCannotReshapeTheFindingsItGrades() throws Exception {
        CliTestSupport.writeSchedulerProject(project);
        CliTestSupport.Run plain = CliTestSupport.run(project,
                "--no-html", "--no-sonar", "-f", "JSON", "-o", project.resolve("build/plain").toString());
        writeConfig("""
                gate:
                  failOn: WARNING
                  maxWarnings: 100
                """);
        CliTestSupport.Run gated = CliTestSupport.run(project,
                "--no-html", "--no-sonar", "-f", "JSON", "-o", project.resolve("build/gated").toString());

        assertEquals(1, gated.exitCode(), "the committed gate still fails the run");
        assertEquals(violations(plain, "build/plain"), violations(gated, "build/gated"),
                "a gate decides pass/fail, never which findings exist");
    }

    /** The findings in one JSON report, by rule id and location. */
    private java.util.List<String> violations(CliTestSupport.Run run, String directory) throws Exception {
        JsonNode json = new ObjectMapper().readTree(
                project.resolve(directory + "/aem-analyser-report.json").toFile());
        java.util.List<String> found = new java.util.ArrayList<>();
        for (JsonNode violation : json.path("violations")) {
            found.add(violation.path("ruleId").asText() + "@" + violation.path("filePath").asText()
                    + ":" + violation.path("startLine").asInt());
        }
        java.util.Collections.sort(found);
        return found;
    }
}
