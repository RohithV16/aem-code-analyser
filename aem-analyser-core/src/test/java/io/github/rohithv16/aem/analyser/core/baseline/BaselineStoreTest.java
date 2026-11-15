package io.github.rohithv16.aem.analyser.core.baseline;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineStoreTest {

    @TempDir
    Path root;

    private Violation violation(String ruleId, String file, int line, String message) {
        return new Violation(ruleId, Severity.WARNING, "SUBTYPE", message, file,
                new TextRegion(line, 1, line, 10), null, null, List.of());
    }

    @Test
    void roundTripsAndFiltersAcceptedFindings() throws Exception {
        AnalysisReport report = new AnalysisReport(root, Map.of(), List.of(
                violation("AEM-DEAD-001", "ui.config/a.cfg.json", 3, "Orphaned config 'a'"),
                violation("AEM-DUP-010", "core/A.java", 10, "Duplicate block in 'A'")), 5);
        Path baselineFile = root.resolve(".aem-analyser-baseline.json");

        BaselineStore.save(baselineFile, report);
        Baseline baseline = BaselineStore.load(baselineFile);
        AnalysisReport filtered = BaselineFilter.filter(report, baseline);

        assertEquals(2, baseline.entries().size());
        assertTrue(filtered.violations().isEmpty(), filtered.violations()::toString);
        assertEquals(2, BaselineFilter.suppressedCount(report, filtered));
    }

    @Test
    void fingerprintIgnoresLineNumbersAndVolatileMessageParts() {
        Violation first = violation("AEM-DEAD-001", "ui.config/a.cfg.json", 3,
                "Orphaned config 'alpha' at line 3");
        Violation moved = violation("AEM-DEAD-001", "ui.config/a.cfg.json", 99,
                "Orphaned config 'beta' at line 99");

        assertEquals(Fingerprint.of(first), Fingerprint.of(moved));
    }

    @Test
    void baselineReportKeepsSuppressedFindingsAtInfo() {
        Violation accepted = violation("AEM-DEAD-001", "ui.config/a.cfg.json", 3, "old");
        AnalysisReport report = new AnalysisReport(root, Map.of(), List.of(accepted), 5);
        Baseline baseline = new Baseline(Baseline.CURRENT_SCHEMA_VERSION, "test",
                List.of(new BaselineEntry(Fingerprint.of(accepted), accepted.ruleId(),
                        accepted.filePath(), accepted.subtype())));

        AnalysisReport filtered = BaselineFilter.filter(report, baseline, true);

        assertEquals(1, filtered.violations().size());
        assertEquals(io.github.rohithv16.aem.analyser.core.model.report.Severity.INFO,
                filtered.violations().get(0).severity());
        assertEquals("SUPPRESSED_BY_BASELINE", filtered.violations().get(0).subtype());
        assertTrue(filtered.violations().get(0).message().startsWith("[baseline]"));
    }

    @Test
    void newFindingIsNotSuppressedByBaseline() throws Exception {
        AnalysisReport accepted = new AnalysisReport(root, Map.of(),
                List.of(violation("AEM-DEAD-001", "ui.config/a.cfg.json", 3, "old")), 5);
        Path baselineFile = root.resolve("baseline.json");
        BaselineStore.save(baselineFile, accepted);

        AnalysisReport current = new AnalysisReport(root, Map.of(),
                List.of(violation("AEM-DEAD-002A", "ui.config/b.cfg.json", 4, "ghost")), 5);
        Baseline baseline = BaselineStore.load(baselineFile);
        AnalysisReport filtered = BaselineFilter.filter(current, baseline);

        assertEquals(1, filtered.violations().size());
        assertFalse(filtered.violations().isEmpty());
    }
}
