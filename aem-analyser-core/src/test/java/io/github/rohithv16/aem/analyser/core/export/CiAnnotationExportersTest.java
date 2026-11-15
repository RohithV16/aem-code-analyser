package io.github.rohithv16.aem.analyser.core.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiAnnotationExportersTest {

    private AnalysisReport report(Severity severity) {
        Violation violation = new Violation("AEM-DEAD-001", severity, "ORPHANED_CONFIGURATION",
                "Orphaned config 100% broken\nsecond line", "core/My,File.java",
                new TextRegion(5, 3, 5, 9), null, "Remove it", List.of());
        return new AnalysisReport(Path.of("/mock"), Map.of(), List.of(violation), 1);
    }

    @Test
    void githubAnnotationsEscapeWorkflowCommandCharacters() throws Exception {
        StringWriter writer = new StringWriter();
        new GitHubAnnotationExporter().export(report(Severity.ERROR), writer);
        String output = writer.toString();

        assertTrue(output.startsWith("::error file=core/My%2CFile.java,line=5,col=3,title=AEM-DEAD-001::"),
                output);
        assertTrue(output.contains("100%25 broken%0Asecond line"), output);
        assertTrue(output.contains("Remediation: Remove it"), output);
    }

    @Test
    void githubUsesNoticeForInfo() throws Exception {
        StringWriter writer = new StringWriter();
        new GitHubAnnotationExporter().export(report(Severity.INFO), writer);
        assertTrue(writer.toString().startsWith("::notice "), writer.toString());
    }

    @Test
    void gitlabCodeQualityJsonHasRequiredFields() throws Exception {
        StringWriter writer = new StringWriter();
        new GitLabCodeQualityExporter().export(report(Severity.WARNING), writer);

        JsonNode issue = new ObjectMapper().readTree(writer.toString()).get(0);
        assertEquals("AEM-DEAD-001", issue.get("check_name").asText());
        assertEquals("major", issue.get("severity").asText());
        assertEquals("core/My,File.java", issue.at("/location/path").asText());
        assertEquals(5, issue.at("/location/lines/begin").asInt());
        assertEquals(16, issue.get("fingerprint").asText().length());
    }

    @Test
    void gitlabDoesNotCloseTheCallerWriter() throws Exception {
        boolean[] closed = {false};
        java.io.Writer writer = new java.io.Writer() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public void write(char[] chars, int offset, int length) {
                buffer.append(chars, offset, length);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };

        new GitLabCodeQualityExporter().export(report(Severity.WARNING), writer);

        assertTrue(!closed[0], "exporter must not close the caller's writer");
    }

    @Test
    void azurePipelinesLoggingCommandIsEmitted() throws Exception {
        StringWriter writer = new StringWriter();
        new AzurePipelinesExporter().export(report(Severity.ERROR), writer);
        String output = writer.toString();

        assertTrue(output.startsWith("##vso[task.logissue type=error;sourcepath=core/My,File.java"), output);
        assertTrue(output.contains("linenumber=5"), output);
        assertTrue(output.contains("code=AEM-DEAD-001]"), output);
        assertTrue(!output.contains("\nsecond line"), "message newlines must be flattened");
    }
}
