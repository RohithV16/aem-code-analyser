package io.github.rohithv16.aem.analyser.core.model.report;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateVerdictTest {

    @Test
    void onlyAFailedGateExitsNonZero() {
        assertEquals(0, GateVerdict.passed().exitCode());
        assertEquals(0, GateVerdict.passedWithWarnings("1 warning(s), 0 info message(s).").exitCode());
        assertEquals(0, GateVerdict.notEnforced("3 error-severity findings, report-only run.").exitCode());
        assertEquals(1, GateVerdict.failed("3 error-severity findings detected.").exitCode());
    }

    @Test
    void renderCombinesHeadlineAndReason() {
        assertEquals("GATE FAILED: 3 error-severity findings detected.",
                GateVerdict.failed("3 error-severity findings detected.").render());
        assertEquals("GATE NOT ENFORCED: 3 error-severity findings, report-only run.",
                GateVerdict.notEnforced("3 error-severity findings, report-only run.").render());
    }

    @Test
    void reportOnlyIsNeverAFailure() {
        GateVerdict verdict = GateVerdict.notEnforced("3 error-severity findings, report-only run.");

        assertFalse(verdict.failed());
        assertTrue(verdict.render().startsWith("GATE NOT ENFORCED"));
    }

    /** Reports built without gate configuration keep the historical findings-only verdict. */
    @Test
    void defaultVerdictFollowsTheFindings() {
        assertEquals(GateVerdict.Status.PASSED, report(List.of()).gateVerdict().status());

        assertEquals(GateVerdict.Status.FAILED,
                report(List.of(violation(Severity.ERROR))).gateVerdict().status());

        assertEquals(GateVerdict.Status.PASSED_WITH_WARNINGS,
                report(List.of(violation(Severity.WARNING))).gateVerdict().status());
    }

    @Test
    void explicitVerdictSurvivesFilteringAndIsCopiedWithTheReport() {
        GateVerdict notEnforced = GateVerdict.notEnforced("2 error-severity findings, report-only run.");
        AnalysisReport report = report(List.of(violation(Severity.ERROR)))
                .withGateVerdict(notEnforced)
                .withViolations(List.of(violation(Severity.ERROR), violation(Severity.ERROR)));

        assertEquals(notEnforced, report.gateVerdict());
    }

    private static AnalysisReport report(List<Violation> violations) {
        return new AnalysisReport(Path.of("/tmp/project"), Map.of(), violations, 1);
    }

    private static Violation violation(Severity severity) {
        return new Violation("AEM-DEAD-001", severity, null, "message", "file.java",
                new io.github.rohithv16.aem.analyser.core.model.graph.TextRegion(1, 1, 1, 1),
                null, null, List.of());
    }
}
