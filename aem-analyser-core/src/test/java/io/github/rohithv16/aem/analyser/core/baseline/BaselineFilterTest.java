package io.github.rohithv16.aem.analyser.core.baseline;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AcceptanceOrigin;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaselineFilterTest {

    private static Violation violation(String file, int line) {
        return new Violation("AEM-DEAD-001", Severity.ERROR, "ORPHANED_CONFIGURATION",
                "Orphaned OSGi Configuration: no matching component", file,
                new TextRegion(line, 1, line, 10), null, null, List.of());
    }

    private static AnalysisReport report(Path root, List<Violation> violations) {
        return new AnalysisReport(root, Map.of(), violations, 0);
    }

    /**
     * Regression: baseline membership used to be a plain set lookup, so ONE accepted finding
     * suppressed every later finding that shared its fingerprint - a silent, permanent amnesty.
     */
    @Test
    void oneAcceptedFindingSuppressesOnlyOneOccurrence(@TempDir Path root) {
        List<Violation> current = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            current.add(violation("ui.config/com.example.Foo.cfg.json", 10 + i));
        }
        Baseline baseline = new Baseline(Baseline.CURRENT_SCHEMA_VERSION, "test",
                List.of(new BaselineEntry(Fingerprint.of(current.get(0)), "AEM-DEAD-001",
                        "ui.config/com.example.Foo.cfg.json", "ORPHANED_CONFIGURATION")));

        AnalysisReport filtered = BaselineFilter.filter(report(root, current), baseline);

        assertEquals(4, filtered.violations().size(),
                "a single baseline entry must not suppress unlimited duplicates");
    }

    @Test
    void matchingNumberOfEntriesSuppressesEverything(@TempDir Path root) {
        List<Violation> current = List.of(
                violation("ui.config/a.cfg.json", 1),
                violation("ui.config/a.cfg.json", 2));
        List<BaselineEntry> entries = new ArrayList<>();
        current.forEach(v -> entries.add(new BaselineEntry(Fingerprint.of(v), "AEM-DEAD-001",
                "ui.config/a.cfg.json", "ORPHANED_CONFIGURATION")));

        AnalysisReport filtered = BaselineFilter.filter(report(root, current),
                new Baseline(Baseline.CURRENT_SCHEMA_VERSION, "test", entries));

        assertEquals(0, filtered.violations().size());
    }

    /**
     * Issue #17: a baselined finding leaves the gate-visible list (counts and exit code unchanged)
     * but stays in the report as an accepted finding at its original severity, so SARIF can show
     * "exists, accepted" instead of deleting the evidence.
     */
    @Test
    void baselinedFindingsMoveToTheAcceptedListWithoutChangingTheirSeverity(@TempDir Path root) {
        Violation error = violation("core/src/main/java/A.java", 1);
        Violation warning = new Violation("AEM-DUP-001", Severity.WARNING, "DUPLICATE_SERVLET",
                "Duplicate servlet registration", "core/src/main/java/A.java",
                new TextRegion(9, 1, 9, 10), null, null, List.of());
        AnalysisReport report = report(root, List.of(error, warning));
        Baseline baseline = new Baseline(Baseline.CURRENT_SCHEMA_VERSION, "test",
                List.of(new BaselineEntry(Fingerprint.of(error), "AEM-DEAD-001",
                        "core/src/main/java/A.java", "ORPHANED_CONFIGURATION")));

        AnalysisReport filtered = BaselineFilter.filter(report, baseline);

        assertEquals(1, filtered.violations().size(), "the gate must still see only the new finding");
        assertEquals(0, filtered.countBySeverity(Severity.ERROR),
                "the accepted error must no longer be counted by the gate");
        assertEquals(1, filtered.acceptedViolations().size());
        Violation accepted = filtered.acceptedViolations().get(0);
        assertEquals(Severity.ERROR, accepted.severity(),
                "acceptance must annotate, not downgrade, the finding");
        assertEquals("ORPHANED_CONFIGURATION", accepted.subtype());
        assertEquals(AcceptanceOrigin.BASELINE, accepted.acceptance());
        assertEquals(1, BaselineFilter.suppressedCount(report, filtered));
    }

    @Test
    void keepSuppressedRetainsTheAcceptedOccurrencesOnly(@TempDir Path root) {
        List<Violation> current = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            current.add(violation("ui.config/a.cfg.json", 5 + i));
        }
        Baseline baseline = new Baseline(Baseline.CURRENT_SCHEMA_VERSION, "test",
                List.of(new BaselineEntry(Fingerprint.of(current.get(0)), "AEM-DEAD-001",
                        "ui.config/a.cfg.json", "ORPHANED_CONFIGURATION")));

        AnalysisReport filtered = BaselineFilter.filter(report(root, current), baseline, true);

        assertEquals(3, filtered.violations().size(), "2 new findings + 1 retained baseline finding");
        assertEquals(1, filtered.violations().stream()
                .filter(v -> "SUPPRESSED_BY_BASELINE".equals(v.subtype())).count());
    }
}
