package io.github.rohithv16.aem.analyser.core.export;

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

/** Unit behaviour of the compiler-style text report (#53). */
class TextReportExporterTest {

    private static Violation violation(Severity severity, String message) {
        return new Violation("AEM-DUP-001", severity, null, message,
                "ui.apps/components/foo/Foo.java", new TextRegion(14, 1, 14, 12),
                null, null, List.of());
    }

    private static String export(AnalysisReport report) throws Exception {
        StringWriter writer = new StringWriter();
        new TextReportExporter().export(report, writer);
        return writer.toString();
    }

    @Test
    void oneFindingIsExactlyOneCompilerLine() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(),
                List.of(violation(Severity.ERROR, "Duplicate servlet endpoint")), 1);

        assertEquals("ui.apps/components/foo/Foo.java:14:1: E AEM-DUP-001 Duplicate servlet endpoint\n",
                export(report));
    }

    @Test
    void severityInitialsAreStableForEveryLevel() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(), List.of(
                violation(Severity.ERROR, "e"),
                violation(Severity.WARNING, "w"),
                violation(Severity.INFO, "i")), 1);

        List<String> lines = export(report).lines().toList();

        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains(": E AEM-DUP-001 e"), lines.get(0));
        assertTrue(lines.get(1).contains(": W AEM-DUP-001 w"), lines.get(1));
        assertTrue(lines.get(2).contains(": I AEM-DUP-001 i"), lines.get(2));
    }

    /** A multi-line message must never produce a second physical line. */
    @Test
    void newlinesInTheMessageAreFolded() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(),
                List.of(violation(Severity.WARNING, "first line\nsecond line\r\n\tthird")), 1);

        String text = export(report);

        assertEquals(1, text.lines().count());
        assertEquals("ui.apps/components/foo/Foo.java:14:1: W AEM-DUP-001"
                + " first line second line third\n", text);
    }

    @Test
    void emptyReportProducesNoLines() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(), List.of(), 0);

        assertEquals("", export(report));
    }
}
