package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit behaviour of the JUnit XML report (#43). */
class JUnitXmlExporterTest {

    private static Violation violation(Severity severity, String message) {
        return new Violation("AEM-DEAD-004", severity, null, message,
                "ui.apps/components/a/a.html", new TextRegion(7, 2, 7, 9),
                "com.example.Pid", "Fix the reference", List.of());
    }

    private static String export(AnalysisReport report) throws Exception {
        StringWriter writer = new StringWriter();
        new JUnitXmlExporter().export(report, writer);
        return writer.toString();
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void suiteCountsFailuresAndSkips() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(), List.of(
                violation(Severity.ERROR, "error one"),
                violation(Severity.WARNING, "warning one"),
                violation(Severity.INFO, "info one")), 1);

        Document document = parse(export(report));
        var suite = document.getDocumentElement();

        assertEquals("testsuite", suite.getTagName());
        assertEquals(JUnitXmlExporter.SUITE_NAME, suite.getAttribute("name"));
        assertEquals("3", suite.getAttribute("tests"));
        assertEquals("2", suite.getAttribute("failures"));
        assertEquals("1", suite.getAttribute("skipped"));
        assertEquals("0", suite.getAttribute("errors"));
        assertEquals("0", suite.getAttribute("time"));
    }

    @Test
    void findingsBecomeTestCasesWithTypedFailures() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(), List.of(
                violation(Severity.ERROR, "error one"),
                violation(Severity.WARNING, "warning one"),
                violation(Severity.INFO, "info one")), 1);

        Document document = parse(export(report));
        var cases = document.getElementsByTagName("testcase");

        assertEquals(3, cases.getLength());
        var errorCase = (org.w3c.dom.Element) cases.item(0);
        assertEquals("AEM-DEAD-004", errorCase.getAttribute("classname"));
        assertEquals("ui.apps/components/a/a.html:7", errorCase.getAttribute("name"));

        var failure = (org.w3c.dom.Element) errorCase.getElementsByTagName("failure").item(0);
        assertNotNull(failure);
        assertEquals("ERROR", failure.getAttribute("type"));
        assertEquals("error one", failure.getAttribute("message"));
        assertTrue(failure.getTextContent().contains("Fix the reference"),
                "the failure body carries the remediation");

        var warningFailure = (org.w3c.dom.Element) ((org.w3c.dom.Element) cases.item(1))
                .getElementsByTagName("failure").item(0);
        assertEquals("WARNING", warningFailure.getAttribute("type"));

        var infoCase = (org.w3c.dom.Element) cases.item(2);
        assertEquals(0, infoCase.getElementsByTagName("failure").getLength());
        assertEquals(1, infoCase.getElementsByTagName("skipped").getLength());
    }

    @Test
    void ruleIdAndTargetPidAreMachineReadable() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(),
                List.of(violation(Severity.ERROR, "boom")), 1);

        var systemOut = parse(export(report)).getElementsByTagName("system-out").item(0);

        assertEquals("rule=AEM-DEAD-004 targetPid=com.example.Pid", systemOut.getTextContent());
    }

    @Test
    void markupInMessagesIsEscapedNotInjected() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(),
                List.of(violation(Severity.ERROR, "a < b & c \"quoted\"")), 1);

        String xml = export(report);
        assertTrue(xml.contains("&lt;") && xml.contains("&amp;"), xml);
        // Parsing is the real assertion: raw markup would either fail or change the tree.
        Document document = parse(xml);
        assertEquals("a < b & c \"quoted\"",
                ((org.w3c.dom.Element) document.getElementsByTagName("failure").item(0))
                        .getAttribute("message"));
    }

    @Test
    void emptyReportIsAValidEmptySuite() throws Exception {
        AnalysisReport report = new AnalysisReport(Path.of("/mock"), Map.of(), List.of(), 0);

        Document document = parse(export(report));

        assertEquals("0", document.getDocumentElement().getAttribute("tests"));
        assertEquals(0, document.getElementsByTagName("testcase").getLength());
    }
}
