package io.github.rohithv16.aem.analyser.core.interop;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AcceptanceOrigin;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #56: a foreign SARIF result suppresses one of our findings only when an explicit id map says
 * the rules are equivalent and the two locations overlap. An unmapped result is coverage only.
 */
class SarifImportTest {

    private static final String SARIF = """
            {
              "version": "2.1.0",
              "runs": [{
                "tool": {"driver": {"name": "sonar"}},
                "results": [{
                  "ruleId": "java:S3649",
                  "locations": [{"physicalLocation": {
                    "artifactLocation": {"uri": "ui.apps/src/main/content/jcr_root/apps/acme/card.html"},
                    "region": {"startLine": 42, "endLine": 42}
                  }}]
                }]
              }]
            }
            """;

    @Test
    void readsToolRuleAndNormalisedLocation(@TempDir Path root) throws Exception {
        Path sarif = root.resolve("other.sarif");
        Files.writeString(sarif, SARIF);

        List<ForeignFinding> findings = SarifImporter.read(sarif, root);

        assertEquals(1, findings.size());
        assertEquals("sonar", findings.get(0).tool());
        assertEquals("java:S3649", findings.get(0).ruleId());
        assertEquals("ui.apps/src/main/content/jcr_root/apps/acme/card.html", findings.get(0).uri());
        assertEquals(42, findings.get(0).startLine());
        assertEquals("sonar java:S3649 @ ui.apps/src/main/content/jcr_root/apps/acme/card.html:42",
                findings.get(0).justification());
    }

    @Test
    void normalisesPercentEncodingAndAbsoluteFileUris(@TempDir Path root) {
        Path file = root.resolve("ui.apps/card one.html").toAbsolutePath();
        String percent = "ui.apps/card%20one.html";
        String absolute = "file://" + file;

        assertEquals("ui.apps/card one.html", SarifImporter.normalizeUri(percent, root));
        assertEquals("ui.apps/card one.html", SarifImporter.normalizeUri(absolute, root));
    }

    @Test
    void mappedAndOverlappingForeignResultAcceptsOurFinding(@TempDir Path root) throws Exception {
        Path sarif = root.resolve("other.sarif");
        Files.writeString(sarif, SARIF);
        List<ForeignFinding> foreign = SarifImporter.read(sarif, root);
        AnalysisReport report = report(qual004(42, 42));

        var result = ExternalSuppressions.apply(report, foreign,
                ImportMap.of(Map.of("java:S3649", "AEM-QUAL-004")));

        assertEquals(0, result.report().violations().size(), "the gate must stop seeing the finding");
        assertEquals(1, result.report().acceptedViolations().size());
        Violation accepted = result.report().acceptedViolations().get(0);
        assertEquals(AcceptanceOrigin.EXTERNAL, accepted.acceptance());
        assertTrue(accepted.acceptanceDetail().startsWith("sonar java:S3649 @ "),
                "the suppression must carry the foreign rule and location: " + accepted.acceptanceDetail());
        assertEquals(1, result.foreignCount());
        assertEquals(1, result.mappedCount());
        assertEquals(1, result.suppressedCount());
    }

    @Test
    void unmappedForeignResultSuppressesNothing(@TempDir Path root) throws Exception {
        Path sarif = root.resolve("other.sarif");
        Files.writeString(sarif, SARIF);
        List<ForeignFinding> foreign = SarifImporter.read(sarif, root);
        AnalysisReport report = report(qual004(42, 42));

        var result = ExternalSuppressions.apply(report, foreign,
                ImportMap.of(Map.of("java:S9999", "AEM-QUAL-004")));

        assertEquals(1, result.report().violations().size(),
                "a map that does not name the foreign rule must not hide the finding");
        assertEquals(0, result.report().acceptedViolations().size());
        assertEquals(1, result.foreignCount());
        assertEquals(0, result.mappedCount());
        assertEquals(0, result.suppressedCount());
    }

    @Test
    void mappedRuleAtAnotherLineDoesNotSuppress(@TempDir Path root) throws Exception {
        Path sarif = root.resolve("other.sarif");
        Files.writeString(sarif, SARIF);
        List<ForeignFinding> foreign = SarifImporter.read(sarif, root);
        AnalysisReport report = report(qual004(43, 43));

        var result = ExternalSuppressions.apply(report, foreign,
                ImportMap.of(Map.of("java:S3649", "AEM-QUAL-004")));

        assertEquals(1, result.report().violations().size(),
                "same rule and file at a different line is a different finding");
        assertEquals(0, result.suppressedCount());
    }

    @Test
    void mappedRuleForAnotherAnalyserRuleDoesNotSuppress(@TempDir Path root) throws Exception {
        Path sarif = root.resolve("other.sarif");
        Files.writeString(sarif, SARIF);
        List<ForeignFinding> foreign = SarifImporter.read(sarif, root);
        AnalysisReport report = report(new Violation("AEM-QUAL-003", Severity.WARNING, "X",
                "scheduler", "ui.apps/src/main/content/jcr_root/apps/acme/card.html",
                new TextRegion(42, 1, 42, 10), null, null, List.of()));

        var result = ExternalSuppressions.apply(report, foreign,
                ImportMap.of(Map.of("java:S3649", "AEM-QUAL-004")));

        assertEquals(1, result.report().violations().size(),
                "a mapped foreign rule must not hide a different analyser rule at the same line");
    }

    @Test
    void oneForeignResultAcceptsOneOccurrence(@TempDir Path root) throws Exception {
        Path sarif = root.resolve("other.sarif");
        Files.writeString(sarif, SARIF);
        List<ForeignFinding> foreign = SarifImporter.read(sarif, root);
        AnalysisReport report = report(qual004(42, 42), qual004(42, 42));

        var result = ExternalSuppressions.apply(report, foreign,
                ImportMap.of(Map.of("java:S3649", "AEM-QUAL-004")));

        assertEquals(1, result.report().violations().size(),
                "one foreign result cannot accept two occurrences");
        assertEquals(1, result.suppressedCount());
    }

    @Test
    void importMapRejectsUnknownTargetAndResolvesRetiredAlias(@TempDir Path root) throws Exception {
        Path bad = root.resolve("bad.yml");
        Files.writeString(bad, "java:S3649: AEM-NOPE-999\n");
        try {
            ImportMap.load(bad);
            throw new AssertionError("an unknown target must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("AEM-NOPE-999"), expected.getMessage());
        }

        Path alias = root.resolve("alias.yml");
        Files.writeString(alias, "java:S3649: AEM-QUAL-006\n");
        assertEquals("AEM-QUAL-004",
                ImportMap.load(alias).targetFor("java:S3649").orElseThrow(),
                "a map naming a retired id must resolve to its replacement");
    }

    private static Violation qual004(int startLine, int endLine) {
        return new Violation("AEM-QUAL-004", Severity.ERROR, "UNSAFE_COMPONENT_STATE",
                "PageManager in a component field",
                "ui.apps/src/main/content/jcr_root/apps/acme/card.html",
                new TextRegion(startLine, 1, endLine, 10), null, null, List.of());
    }

    private static AnalysisReport report(Violation... violations) {
        return new AnalysisReport(Path.of("/tmp/import-project"), Map.of(), List.of(violations), 1);
    }
}
