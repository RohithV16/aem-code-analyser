package io.github.rohithv16.aem.analyser.maven;

import io.github.rohithv16.aem.analyser.core.export.SarifReportExporter;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #47: the delta gate's semantics.
 *
 * <p>The gate exists to fail on <em>new</em> findings only, so the tests pin the three outcomes the
 * issue names — new, unchanged, fixed — plus the cases that make a delta gate unusable if wrong:
 * moved code must not churn, duplicate findings must be counted as a multiset, and the severity
 * threshold must actually decide the exit code.
 */
class DiffGateTest {

    private static final Path ROOT = Path.of("/mock/project");

    @Test
    void aNewFindingIsReportedAsNewAndAnIdenticalOneAsUnchanged() {
        List<FindingSet.Finding> previous = FindingSet.fromViolations(List.of(
                violation("AEM-DEAD-001", Severity.ERROR, "core/src/A.java", "class A is unused")));
        List<FindingSet.Finding> current = FindingSet.fromViolations(List.of(
                violation("AEM-DEAD-001", Severity.ERROR, "core/src/A.java", "class A is unused"),
                violation("AEM-DEAD-001", Severity.ERROR, "core/src/B.java", "class B is unused")));

        ReportDiff.Result result = ReportDiff.compute(previous, current);

        assertEquals(1, result.added().size(), result.added().toString());
        assertEquals("core/src/B.java", result.added().get(0).path());
        assertEquals(0, result.resolved().size());
        assertEquals(1, result.unchanged().size());
    }

    @Test
    void aFixedFindingIsReportedAsResolvedAndNothingElseMoves() {
        List<FindingSet.Finding> previous = FindingSet.fromViolations(List.of(
                violation("AEM-DEAD-001", Severity.ERROR, "core/src/A.java", "class A is unused"),
                violation("AEM-DEAD-001", Severity.ERROR, "core/src/B.java", "class B is unused")));
        List<FindingSet.Finding> current = FindingSet.fromViolations(List.of(
                violation("AEM-DEAD-001", Severity.ERROR, "core/src/A.java", "class A is unused")));

        ReportDiff.Result result = ReportDiff.compute(previous, current);

        assertEquals(0, result.added().size());
        assertEquals(1, result.resolved().size());
        assertEquals("core/src/B.java", result.resolved().get(0).path());
        assertEquals(1, result.unchanged().size());
    }

    @Test
    void shiftingLinesInAFileCausesNoChurn() {
        Violation before = violationAt("AEM-DUP-001", Severity.WARNING, "core/src/A.java", "duplicate block", 10, 20);
        Violation after = violationAt("AEM-DUP-001", Severity.WARNING, "core/src/A.java", "duplicate block", 90, 100);

        ReportDiff.Result result = ReportDiff.compute(
                FindingSet.fromViolations(List.of(before)),
                FindingSet.fromViolations(List.of(after)));

        assertEquals(0, result.added().size(), "a moved finding is the same finding");
        assertEquals(0, result.resolved().size());
        assertEquals(1, result.unchanged().size());
    }

    @Test
    void duplicateFindingsAreMatchedAsAMultiset() {
        List<FindingSet.Finding> previous = FindingSet.fromViolations(List.of(
                violation("AEM-DEAD-001", Severity.ERROR, "core/src/A.java", "same message"),
                violation("AEM-DEAD-001", Severity.ERROR, "core/src/A.java", "same message")));
        List<FindingSet.Finding> current = FindingSet.fromViolations(List.of(
                violation("AEM-DEAD-001", Severity.ERROR, "core/src/A.java", "same message")));

        ReportDiff.Result result = ReportDiff.compute(previous, current);

        assertEquals(0, result.added().size());
        assertEquals(1, result.resolved().size(), "one of two identical findings disappeared");
        assertEquals(1, result.unchanged().size());
    }

    @Test
    void aWindowsPathAndADotPrefixStillMatch() {
        List<FindingSet.Finding> previous = FindingSet.fromViolations(List.of(
                violation("AEM-DEAD-001", Severity.ERROR, "core/src/A.java", "unused")));
        List<FindingSet.Finding> current = FindingSet.fromViolations(List.of(
                violation("AEM-DEAD-001", Severity.ERROR, "./core\\src\\A.java", "unused")));

        assertEquals(0, ReportDiff.compute(previous, current).added().size());
    }

    @Test
    void theThresholdDecidesWhetherNewFindingsFailTheGate() {
        ReportDiff.Result result = ReportDiff.compute(List.of(), FindingSet.fromViolations(List.of(
                violation("AEM-DEAD-001", Severity.ERROR, "core/src/A.java", "unused"),
                violation("AEM-CLOUD-004", Severity.WARNING, "core/src/B.java", "deprecated api"),
                violation("AEM-DEAD-003", Severity.INFO, "core/src/C.java", "note"))));

        assertEquals(3, result.addedAtOrAbove(Severity.INFO));
        assertEquals(2, result.addedAtOrAbove(Severity.WARNING));
        assertEquals(1, result.addedAtOrAbove(Severity.ERROR));
        assertEquals(0, result.addedAtOrAbove(null), "failOnNew=NONE is report-only");
    }

    @Test
    void failOnNewIsParsedCaseInsensitivelyAndUnknownValuesAreRejected() throws MojoExecutionException {
        assertEquals(Severity.ERROR, DiffMojo.normaliseThreshold("error", "aem-analyser.failOnNew"));
        assertEquals(Severity.WARNING, DiffMojo.normaliseThreshold(" Warning ", "aem-analyser.failOnNew"));
        assertEquals(Severity.INFO, DiffMojo.normaliseThreshold("INFO", "aem-analyser.failOnNew"));
        assertNull(DiffMojo.normaliseThreshold("NONE", "aem-analyser.failOnNew"));
        assertNull(DiffMojo.normaliseThreshold(null, "aem-analyser.failOnNew"));

        MojoExecutionException thrown = assertThrows(MojoExecutionException.class,
                () -> DiffMojo.normaliseThreshold("critical", "aem-analyser.failOnNew"));
        assertTrue(thrown.getMessage().contains("aem-analyser.failOnNew=critical"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("NONE, ERROR, WARNING, INFO"), thrown.getMessage());
    }

    @Test
    void aSarifDocumentIsParsedByStructureNotByStringMatching() throws IOException {
        String sarif = """
                {"version":"2.1.0","runs":[{"results":[
                  {"ruleId":"AEM-DEAD-001","level":"error",
                   "message":{"text":"class A is unused. Remediation: delete it"},
                   "partialFingerprints":{"aemAnalyserFingerprint/v1":"abc123"},
                   "locations":[{"physicalLocation":{"artifactLocation":{
                     "uri":"ui.apps/src/main/content/jcr%20root/.content.xml"}}}]},
                  {"ruleId":"AEM-QUAL-003","level":"note","message":{"text":"a {weird} message"},
                   "locations":[{"physicalLocation":{"artifactLocation":{"uri":"core/src/A.java"}}}]}
                ]}]}
                """;

        List<FindingSet.Finding> findings = FindingSet.read(sarif);

        assertEquals(2, findings.size());
        FindingSet.Finding first = findings.get(0);
        assertEquals("AEM-DEAD-001", first.ruleId());
        assertEquals(Severity.ERROR, first.severity());
        assertEquals("abc123", first.fingerprint());
        assertEquals("ui.apps/src/main/content/jcr root/.content.xml", first.path(),
                "percent-encoded SARIF uris must be decoded back to project paths");
        assertEquals("class A is unused.", first.message(), "the appended remediation is not the message");
        assertNull(findings.get(1).fingerprint(), "a result without fingerprints must fall back to content");
        assertEquals("a {weird} message", findings.get(1).message());
        assertEquals(Severity.INFO, findings.get(1).severity(), "SARIF 'note' is the analyser's INFO");
    }

    @Test
    void aBaselineDocumentIsReadAsItsFingerprints() throws IOException {
        String baseline = """
                {"schemaVersion":2,"toolVersion":"1.1.0-SNAPSHOT","entries":[
                  {"fingerprint":"abc123","ruleId":"AEM-DEAD-001","filePath":"core/src/A.java","subtype":"UNUSED"}
                ]}
                """;

        List<FindingSet.Finding> findings = FindingSet.read(baseline);

        assertEquals(1, findings.size());
        assertEquals("abc123", findings.get(0).key());
        assertEquals("AEM-DEAD-001", findings.get(0).ruleId());
    }

    @Test
    void anUnrecognisedDocumentIsRejectedInsteadOfSilentlyReadingZeroFindings() {
        IOException thrown = assertThrows(IOException.class, () -> FindingSet.read("{\"foo\":1}"));
        assertTrue(thrown.getMessage().contains("'runs'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'entries'"), thrown.getMessage());
    }

    @Test
    void aRealSarifExportRoundTripsThroughTheParserWithNoChurn() throws Exception {
        List<Violation> violations = List.of(
                violation("AEM-DEAD-001", Severity.ERROR, "core/src/A.java", "class A is unused"),
                violation("AEM-CLOUD-004", Severity.WARNING, "ui.apps/.content.xml", "deprecated api"));
        StringWriter writer = new StringWriter();
        new SarifReportExporter().export(
                new AnalysisReport(ROOT, Map.of(), violations, 1L), writer);

        ReportDiff.Result result = ReportDiff.compute(
                FindingSet.read(writer.toString()), FindingSet.fromViolations(violations));

        assertEquals(0, result.added().size(), "the exporter and the parser disagree on identity");
        assertEquals(0, result.resolved().size());
        assertEquals(2, result.unchanged().size());
    }

    @Test
    void theTableAndTheJsonAgreeOnTheDelta() throws Exception {
        ReportDiff.Result result = ReportDiff.compute(
                FindingSet.fromViolations(List.of(
                        violation("AEM-DEAD-001", Severity.ERROR, "core/src/A.java", "unused"),
                        violation("AEM-DUP-001", Severity.WARNING, "core/src/B.java", "duplicate"))),
                FindingSet.fromViolations(List.of(
                        violation("AEM-DEAD-001", Severity.ERROR, "core/src/A.java", "unused"),
                        violation("AEM-CLOUD-004", Severity.WARNING, "core/src/C.java", "deprecated"))));

        List<String> table = ReportDiff.renderTable(result);
        assertEquals("AEM Code Analyser diff: 1 new, 1 resolved, 1 unchanged", table.get(0));
        assertTrue(String.join("\n", table).contains("AEM-CLOUD-004"), String.join("\n", table));
        assertTrue(String.join("\n", table).contains("TOTAL"), String.join("\n", table));

        String json = ReportDiff.toJson(result, "/tmp/a.sarif", "/work/portfolio");
        assertTrue(json.contains("\"new\" : 1"), json);
        assertTrue(json.contains("\"resolved\" : 1"), json);
        assertTrue(json.contains("\"unchanged\" : 1"), json);
        assertFalse(json.contains("aemAnalyserFingerprint"), "the document carries no fingerprints");
    }

    @Test
    void anEmptyDeltaRendersWithoutAFakeRuleTable() {
        List<String> lines = ReportDiff.renderTable(ReportDiff.compute(List.of(), List.of()));

        assertEquals(2, lines.size(), String.join("\n", lines));
        assertTrue(lines.get(1).contains("no findings"), lines.get(1));
    }

    private static Violation violation(String ruleId, Severity severity, String path, String message) {
        return violationAt(ruleId, severity, path, message, 1, 1);
    }

    private static Violation violationAt(String ruleId, Severity severity, String path, String message,
                                         int startLine, int endLine) {
        return new Violation(ruleId, severity, "SUBTYPE", message, path,
                new TextRegion(startLine, 1, endLine, 10), null, null, List.of());
    }
}
