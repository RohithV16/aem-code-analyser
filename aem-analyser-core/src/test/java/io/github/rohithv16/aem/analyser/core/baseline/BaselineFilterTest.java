package io.github.rohithv16.aem.analyser.core.baseline;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
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
