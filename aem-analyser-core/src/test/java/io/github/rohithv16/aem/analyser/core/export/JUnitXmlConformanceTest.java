package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural conformance for the JUnit XML document a CI test tab consumes: one testcase per
 * finding, failures for ERROR/WARNING, skipped for INFO, and byte identity across runs.
 */
class JUnitXmlConformanceTest {

    private static AnalysisReport portfolioShapedReport() {
        List<String> ruleIds = new ArrayList<>(RuleRegistry.all().stream()
                .map(rule -> rule.id()).distinct().toList());
        List<Violation> violations = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            Severity severity = i < 7 ? Severity.ERROR
                    : i < 35 ? Severity.WARNING : Severity.INFO;
            violations.add(new Violation(ruleIds.get(i % ruleIds.size()), severity, null,
                    "Finding " + i, "ui.apps/components/c" + i + ".html",
                    new TextRegion(i + 1, 1, i + 1, 12), null, null, List.of()));
        }
        return new AnalysisReport(Path.of("/mock"), Map.of("modules", 1), violations, 12);
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
    void productionShapedRunYields36TestCases35FailuresAnd1Skip() throws Exception {
        Document document = parse(export(portfolioShapedReport()));

        assertEquals(36, document.getElementsByTagName("testcase").getLength());
        assertEquals(35, document.getElementsByTagName("failure").getLength());
        assertEquals(1, document.getElementsByTagName("skipped").getLength());

        var suite = document.getDocumentElement();
        assertEquals("36", suite.getAttribute("tests"));
        assertEquals("35", suite.getAttribute("failures"));
        assertEquals("1", suite.getAttribute("skipped"));
    }

    @Test
    void documentIsByteIdenticalAcrossTwoRuns() throws Exception {
        AnalysisReport report = portfolioShapedReport();
        String first = export(report);
        String second = export(report);

        assertEquals(first, second);
        assertEquals(sha256(first), sha256(second));
        assertTrue(first.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(first.endsWith("</testsuite>\n"));
    }

    @Test
    void everyTestCaseHasARuleClassnameAndFileLineName() throws Exception {
        Document document = parse(export(portfolioShapedReport()));
        var cases = document.getElementsByTagName("testcase");

        for (int i = 0; i < cases.getLength(); i++) {
            var element = (org.w3c.dom.Element) cases.item(i);
            assertTrue(element.getAttribute("classname").matches("AEM-[A-Z][A-Z0-9]*-[0-9]+[A-Z]?"),
                    "classname must be the rule id, got " + element.getAttribute("classname"));
            assertTrue(element.getAttribute("name").matches(".+\\.html:[1-9][0-9]*"),
                    "name must be file:line, got " + element.getAttribute("name"));
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
