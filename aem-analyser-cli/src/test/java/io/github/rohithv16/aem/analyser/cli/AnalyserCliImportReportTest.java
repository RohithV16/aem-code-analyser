package io.github.rohithv16.aem.analyser.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #56: importing another analyser's SARIF is the SonarQube migration route. Only a mapped,
 * overlapping foreign result accepts our finding; an unmapped rule is coverage only.
 */
class AnalyserCliImportReportTest {

    private static final String FOREIGN_SARIF = """
            {"version":"2.1.0","runs":[{
              "tool":{"driver":{"name":"sonar"}},
              "results":[{"ruleId":"java:S3649","locations":[{"physicalLocation":{
                "artifactLocation":{"uri":"core/src/main/java/com/acme/Cards.java"},
                "region":{"startLine":8,"endLine":8}}}]}]
            }]}
            """;

    @Test
    void mappedOverlappingResultAcceptsTheFindingAndPassesTheGate(@TempDir Path project) throws Exception {
        CliTestSupport.writePageManagerProject(project);
        Path foreign = project.resolve("other.sarif");
        Files.writeString(foreign, FOREIGN_SARIF);
        Path map = project.resolve("zzmap.yml");
        Files.writeString(map, "java:S3649: AEM-QUAL-004\n");
        Path out = project.resolve("out");

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar",
                "-f", "SARIF", "-o", out.toString(),
                "--import-report", foreign.toString(), "--import-map", map.toString());

        assertEquals(0, run.exitCode(), "an externally accepted finding must not fail the gate:\n"
                + run.output() + run.errors());
        assertTrue(run.errors().contains("1 finding(s) accepted as external"), run.errors());
        JsonNode result = onlyResult(out);
        assertEquals("AEM-QUAL-004", result.path("ruleId").asText());
        assertEquals("external", result.path("suppressions").path(0).path("kind").asText());
        assertEquals("accepted", result.path("suppressions").path(0).path("status").asText());
        assertEquals("sonar java:S3649 @ core/src/main/java/com/acme/Cards.java:8",
                result.path("suppressions").path(0).path("justification").asText());
    }

    @Test
    void withoutAMapTheFindingStaysActionable(@TempDir Path project) throws Exception {
        CliTestSupport.writePageManagerProject(project);
        Path foreign = project.resolve("other.sarif");
        Files.writeString(foreign, FOREIGN_SARIF);
        Path out = project.resolve("out");

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar",
                "-f", "SARIF", "-o", out.toString(), "--import-report", foreign.toString());

        assertEquals(1, run.exitCode(), "no map means no suppression, so the ERROR still fails");
        assertTrue(run.errors().contains("0 mapped, 1 unmapped"), run.errors());
        JsonNode result = onlyResult(out);
        assertTrue(result.path("suppressions").isMissingNode(),
                "an unmapped foreign result must suppress nothing");
    }

    @Test
    void unmappedForeignRuleSuppressesNothing(@TempDir Path project) throws Exception {
        CliTestSupport.writePageManagerProject(project);
        Path foreign = project.resolve("other.sarif");
        Files.writeString(foreign, FOREIGN_SARIF);
        Path map = project.resolve("zzmap.yml");
        Files.writeString(map, "java:S9999: AEM-QUAL-004\n");
        Path out = project.resolve("out");

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar",
                "-f", "SARIF", "-o", out.toString(),
                "--import-report", foreign.toString(), "--import-map", map.toString());

        assertEquals(1, run.exitCode(), "a map that does not name the foreign rule cannot hide a finding");
        assertTrue(run.errors().contains("0 mapped, 1 unmapped"), run.errors());
        assertTrue(onlyResult(out).path("suppressions").isMissingNode());
    }

    @Test
    void importMapWithoutReportIsAUsageError(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path map = project.resolve("zzmap.yml");
        Files.writeString(map, "java:S3649: AEM-QUAL-004\n");

        CliTestSupport.Run run = CliTestSupport.run(project, "--import-map", map.toString());

        assertEquals(2, run.exitCode());
        assertTrue(run.errors().contains("--import-map requires --import-report"), run.errors());
    }

    private static JsonNode onlyResult(Path out) throws Exception {
        JsonNode sarif = new ObjectMapper().readTree(Files.readString(out.resolve("aem-analyser.sarif")));
        JsonNode results = sarif.at("/runs/0/results");
        assertNotNull(results);
        assertEquals(1, results.size(), "the fixture has exactly one finding: " + results);
        assertFalse(results.get(0).path("ruleId").asText().isBlank());
        return results.get(0);
    }
}
