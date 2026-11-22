package io.github.rohithv16.aem.analyser.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring for the three formats added in #36/#53/#43: the CLI must accept each name, document it and
 * write the documented file under {@code -o}.
 */
class AnalyserCliFormatSwitchTest {

    @Test
    void helpListsTheNewFormats() {
        String usage = new CommandLine(new AnalyserCli()).getUsageMessage();

        assertTrue(usage.contains("TEXT"), usage);
        assertTrue(usage.contains("JSON"), usage);
        assertTrue(usage.contains("JUNIT"), usage);
    }

    @Test
    void allThreeFormatsAreWrittenInOneRun(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path output = project.resolve("out");

        CliTestSupport.Run run = CliTestSupport.run(project,
                "-f", "JSON,TEXT,JUNIT", "-o", output.toString(), "--no-html", "--no-sonar");

        assertEquals(0, run.exitCode(), run.output());

        Path json = output.resolve("aem-analyser-report.json");
        Path text = output.resolve("aem-analyser.txt");
        Path junit = output.resolve("aem-analyser-junit.xml");
        assertTrue(Files.isRegularFile(json), "JSON report missing: " + run.output());
        assertTrue(Files.isRegularFile(text), "text report missing: " + run.output());
        assertTrue(Files.isRegularFile(junit), "JUnit report missing: " + run.output());

        JsonNode root = new ObjectMapper().readTree(Files.readString(json));
        assertEquals(1, root.get("schemaVersion").asInt());
        assertTrue(root.get("violations").isArray());

        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(junit.toFile());
        assertEquals("testsuite", document.getDocumentElement().getTagName());

        // A project with no findings emits no compiler lines at all (not even a blank line).
        assertEquals("", Files.readString(text));
    }

    @Test
    void eachFormatAlsoAcceptsAnExplicitFileName(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        Path junit = project.resolve("reports/tests.xml");

        CliTestSupport.Run run = CliTestSupport.run(project,
                "-f", "JUNIT", "-o", junit.toString(), "--no-html", "--no-sonar");

        assertEquals(0, run.exitCode(), run.output());
        assertTrue(Files.isRegularFile(junit), "an .xml -o path must be treated as a file");
    }
}
