package io.github.rohithv16.aem.analyser.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #54: {@code --diagnose RULE:PATH:LINE} writes one local, redacted repro bundle and refuses a
 * selector that names no finding in the run.
 */
class AnalyserCliDiagnoseTest {

    @Test
    void writesARedactedBundleForARealFinding(@TempDir Path project) throws Exception {
        CliTestSupport.writePageManagerProject(project);
        Path out = project.resolve("diag");

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar",
                "-o", out.toString(),
                "--diagnose", "AEM-QUAL-004:core/src/main/java/com/acme/Cards.java:8");

        assertEquals(0, run.exitCode(), run.output() + run.errors());
        Path zip = out.resolve("aem-analyser-diagnose-AEM-QUAL-004.zip");
        assertTrue(Files.isRegularFile(zip), "the bundle must be written: " + run.errors());

        Map<String, String> entries = readZip(zip);
        assertEquals(4, entries.size(), "the archive has four entries: " + entries.keySet());
        assertTrue(entries.containsKey("issue.md"));
        assertTrue(entries.containsKey("finding.json"));
        assertTrue(entries.containsKey("excerpt.txt"));
        assertTrue(entries.containsKey("environment.json"));
        for (String content : entries.values()) {
            assertFalse(content.contains("com.acme"),
                    "the bundle must not leak the project groupId/package prefix:\n" + content);
        }
        assertTrue(entries.get("excerpt.txt").contains("private PageManager pageManager;"),
                "the offending line survives redaction: " + entries.get("excerpt.txt"));
        assertTrue(entries.get("issue.md").contains("AEM-QUAL-004"));
    }

    @Test
    void rejectsASelectorThatIsNotAFindingInThisRun(@TempDir Path project) throws Exception {
        CliTestSupport.writePageManagerProject(project);
        Path out = project.resolve("diag");

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar",
                "-o", out.toString(),
                "--diagnose", "AEM-DEAD-001:core/src/main/java/com/acme/Cards.java:1");

        assertEquals(2, run.exitCode(), "a selector naming no finding is a usage error");
        assertTrue(run.errors().contains("not a finding in this run"), run.errors());
        assertFalse(Files.exists(out.resolve("aem-analyser-diagnose-AEM-DEAD-001.zip")),
                "no bundle may be written for a finding that does not exist");
    }

    @Test
    void rejectsMalformedSelector(@TempDir Path project) throws Exception {
        CliTestSupport.writePageManagerProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project, "--no-html", "--no-sonar",
                "--diagnose", "AEM-QUAL-004/Cards.java:8");

        assertEquals(2, run.exitCode());
        assertTrue(run.errors().contains("Expected RULE:PATH:LINE"), run.errors());
    }

    private static Map<String, String> readZip(Path zipFile) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
