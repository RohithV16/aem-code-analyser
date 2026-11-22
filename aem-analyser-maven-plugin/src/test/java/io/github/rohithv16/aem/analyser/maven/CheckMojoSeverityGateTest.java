package io.github.rohithv16.aem.analyser.maven;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Precedence tests for the {@code aem-analyser:check} severity gate.
 *
 * <p>These exercise the real gate logic (not a Maven harness): {@code failOn}, {@code failOnError}
 * and {@code failOnWarning} must agree with the CLI's {@code --fail-on} value space, and the failure
 * message must name the rules that actually fired.
 */
class CheckMojoSeverityGateTest {

    private static final Path ROOT = Path.of("/mock/project");

    @Test
    void failOnNoneIsReportOnlyEvenWhenEveryGateSwitchIsOn() {
        AnalysisReport report = report(
                violation("AEM-DUP-001", Severity.ERROR),
                violation("AEM-CLOUD-004", Severity.WARNING),
                violation("AEM-DEAD-003", Severity.INFO));

        Optional<String> failure = CheckMojo.severityFailure("NONE", true, true, report);

        assertTrue(failure.isEmpty(), "failOn=NONE must never fail the build: " + failure);
    }

    @Test
    void failOnNoneIsCaseInsensitiveAndTrimmed() throws MojoExecutionException {
        assertEquals("NONE", CheckMojo.normaliseFailOn(" none "));
        assertEquals("WARNING", CheckMojo.normaliseFailOn("warning"));
        assertNull(CheckMojo.normaliseFailOn("  "));
        assertNull(CheckMojo.normaliseFailOn(null));
    }

    @Test
    void unknownFailOnIsRejectedWithTheAcceptedSet() {
        MojoExecutionException thrown =
                assertThrows(MojoExecutionException.class, () -> CheckMojo.normaliseFailOn("BOGUS"));

        String message = thrown.getMessage();
        assertTrue(message.contains("aem-analyser.failOn=BOGUS"), message);
        assertTrue(message.contains("NONE, ERROR, WARNING, INFO"), message);
    }

    @Test
    void defaultGateNamesTheRuleThatActuallyFiredNotDeadCode() {
        AnalysisReport report = report(
                violation("AEM-DUP-001", Severity.ERROR),
                violation("AEM-CLOUD-004", Severity.WARNING));

        String message = CheckMojo.severityFailure(null, true, false, report).orElseThrow();

        assertTrue(message.contains("AEM-DUP-001"), message);
        assertTrue(message.contains("at or above severity ERROR"), message);
        assertFalse(message.contains("AEM-DEAD"), "the message must not blame AEM-DEAD rules: " + message);
        assertFalse(message.contains("AEM-CLOUD-004"),
                "a message about error-severity findings must not list warning-only rules: " + message);
    }

    @Test
    void defaultGatePassesWithOnlyWarnings() {
        AnalysisReport report = report(violation("AEM-CLOUD-004", Severity.WARNING));

        assertTrue(CheckMojo.severityFailure(null, true, false, report).isEmpty());
    }

    @Test
    void failOnErrorExplicitThresholdPassesWithOnlyWarnings() {
        AnalysisReport report = report(violation("AEM-CLOUD-004", Severity.WARNING));

        assertTrue(CheckMojo.severityFailure("ERROR", true, false, report).isEmpty());
    }

    @Test
    void failOnWarningThresholdFailsOnWarningsAndNamesThem() {
        AnalysisReport report = report(violation("AEM-PERF-004", Severity.WARNING));

        String message = CheckMojo.severityFailure("WARNING", false, false, report).orElseThrow();

        assertTrue(message.contains("AEM-PERF-004"), message);
        assertTrue(message.contains("severity WARNING"), message);
    }

    @Test
    void failOnInfoThresholdFailsOnInfos() {
        AnalysisReport report = report(violation("AEM-DEAD-003", Severity.INFO));

        String message = CheckMojo.severityFailure("INFO", false, false, report).orElseThrow();

        assertTrue(message.contains("AEM-DEAD-003"), message);
        assertTrue(message.contains("severity INFO"), message);
    }

    @Test
    void failOnWarningSwitchStillFailsUnderAnErrorThreshold() {
        AnalysisReport report = report(violation("AEM-CLOUD-004", Severity.WARNING));

        String message = CheckMojo.severityFailure("ERROR", true, true, report).orElseThrow();

        assertTrue(message.contains("AEM-CLOUD-004"), message);
    }

    @Test
    void noFindingsNeverFails() {
        AnalysisReport report = report();

        assertTrue(CheckMojo.severityFailure(null, true, true, report).isEmpty());
        assertTrue(CheckMojo.severityFailure("INFO", true, true, report).isEmpty());
    }

    @Test
    void ruleIdsAreDeduplicatedAndDeterministic() {
        AnalysisReport report = report(
                violation("AEM-DUP-001", Severity.ERROR),
                violation("AEM-DUP-001", Severity.ERROR),
                violation("AEM-DEAD-002", Severity.ERROR));

        String message = CheckMojo.severityFailure(null, true, false, report).orElseThrow();

        assertEquals(1, count(message, "AEM-DUP-001"), message);
        assertEquals(1, count(message, "AEM-DEAD-002"), message);
        assertTrue(message.contains("(rules: AEM-DEAD-002, AEM-DUP-001)"), message);
        assertTrue(message.contains("3 finding(s)"), message);
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static AnalysisReport report(Violation... violations) {
        return new AnalysisReport(ROOT, Map.of(), List.of(violations), 1L);
    }

    private static Violation violation(String ruleId, Severity severity) {
        return new Violation(ruleId, severity, "TEST", "test finding", "src/main/java/A.java",
                TextRegion.forWholeFile(), null, null, List.of());
    }
}
