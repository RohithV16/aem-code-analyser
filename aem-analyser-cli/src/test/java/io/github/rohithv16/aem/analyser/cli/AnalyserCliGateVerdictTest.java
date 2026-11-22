package io.github.rohithv16.aem.analyser.cli;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.CodeMetrics;
import io.github.rohithv16.aem.analyser.core.model.report.CoverageReport;
import io.github.rohithv16.aem.analyser.core.model.report.GateVerdict;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verdict and the exit code are one decision, taken once from the effective gate. A run must not
 * print "GATE FAILED" and exit 0, and a threshold breach must not exit 1 while the verdict reads
 * "GATE PASSED".
 */
class AnalyserCliGateVerdictTest {

    @Test
    void reportOnlyVerdictIsNotEnforcedAndExitsZero() {
        AnalyserCli cli = cliWith("--fail-on", "NONE");

        GateVerdict verdict = cli.evaluateGate(
                CliTestSupport.reportWith(CliTestSupport.errorViolation()));

        assertEquals(GateVerdict.Status.NOT_ENFORCED, verdict.status(), verdict.render());
        assertEquals(0, verdict.exitCode());
        assertTrue(verdict.render().startsWith("GATE NOT ENFORCED: "), verdict.render());
        assertFalse(verdict.render().contains("GATE FAILED"), verdict.render());
    }

    @Test
    void defaultGateFailsOnErrorFindings() {
        AnalyserCli cli = cliWith("--no-html");

        GateVerdict verdict = cli.evaluateGate(
                CliTestSupport.reportWith(CliTestSupport.errorViolation()));

        assertEquals(GateVerdict.Status.FAILED, verdict.status());
        assertEquals(1, verdict.exitCode());
        assertEquals("GATE FAILED: 1 error-severity finding detected.", verdict.render());
    }

    @Test
    void failOnErrorVerdictMatchesExitCode() {
        AnalyserCli cli = cliWith("--fail-on", "ERROR");

        GateVerdict verdict = cli.evaluateGate(
                CliTestSupport.reportWith(CliTestSupport.errorViolation()));

        assertEquals(GateVerdict.Status.FAILED, verdict.status());
        assertEquals(1, verdict.exitCode());
    }

    @Test
    void warningsOnlyReportPassesWithWarnings() {
        AnalyserCli cli = cliWith("--no-html");

        GateVerdict verdict = cli.evaluateGate(
                CliTestSupport.reportWith(CliTestSupport.warningViolation()));

        assertEquals(GateVerdict.Status.PASSED_WITH_WARNINGS, verdict.status());
        assertEquals(0, verdict.exitCode());
    }

    @Test
    void failOnWarningFlipsTheSameRunToFailed() {
        AnalyserCli cli = cliWith("--no-html", "--fail-on-warning");

        GateVerdict verdict = cli.evaluateGate(
                CliTestSupport.reportWith(CliTestSupport.warningViolation()));

        assertEquals(GateVerdict.Status.FAILED, verdict.status(), verdict.render());
        assertEquals(1, verdict.exitCode());
        assertTrue(verdict.render().contains("--fail-on-warning"), verdict.render());
    }

    /** Report-only mode still honours the thresholds: only the implicit error gate is switched off. */
    @Test
    void reportOnlyStillHonoursQualityThresholds() {
        AnalyserCli cli = cliWith("--fail-on", "NONE", "--max-duplication", "1.0");
        AnalysisReport report = new AnalysisReport(Path.of("/tmp/project"), Map.of(),
                List.of(CliTestSupport.errorViolation()), 1, CoverageReport.notFound(),
                new CodeMetrics(1, 100, 1, 10, 1, 6.2));

        GateVerdict verdict = cli.evaluateGate(report);

        assertEquals(GateVerdict.Status.FAILED, verdict.status(), verdict.render());
        assertEquals(1, verdict.exitCode());
        assertTrue(verdict.render().contains("duplication 6.2% exceeds --max-duplication 1.0%"),
                verdict.render());
    }

    /** A coverage threshold has to change the verdict line, not just the exit code. */
    @Test
    void coverageThresholdFailureIsNamedInVerdict() {
        AnalyserCli cli = cliWith("--min-coverage", "80");
        AnalysisReport report = new AnalysisReport(Path.of("/tmp/project"), Map.of(),
                List.of(CliTestSupport.infoViolation()), 1,
                new CoverageReport(true, "core/target/site/jacoco/jacoco.csv", 10, 100, 10.0, 0, 0, 0.0),
                new CodeMetrics(1, 100, 1, 0, 0, 0.0));

        GateVerdict verdict = cli.evaluateGate(report);

        assertEquals(GateVerdict.Status.FAILED, verdict.status());
        assertEquals(1, verdict.exitCode());
        assertTrue(verdict.render().contains("line coverage 10.0% is below --min-coverage 80.0%"),
                verdict.render());
    }

    @Test
    void maxWarningsThresholdIsNamedInVerdict() {
        AnalyserCli cli = cliWith("--max-warnings", "0");

        GateVerdict verdict = cli.evaluateGate(
                CliTestSupport.reportWith(CliTestSupport.warningViolation()));

        assertEquals(GateVerdict.Status.FAILED, verdict.status());
        assertTrue(verdict.render().contains("exceed --max-warnings 0"), verdict.render());
    }

    @Test
    void emptyReportPassesEvenInReportOnlyMode() {
        AnalyserCli cli = cliWith("--fail-on", "NONE");

        GateVerdict verdict = cli.evaluateGate(CliTestSupport.reportWith());

        assertEquals(GateVerdict.Status.PASSED, verdict.status());
        assertEquals(0, verdict.exitCode());
    }

    @Test
    void reportOnlyRunEndToEndExitsZeroWithoutGateFailed(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);

        CliTestSupport.Run run = CliTestSupport.run(project,
                "--no-html", "--no-sonar", "--fail-on", "NONE");

        assertEquals(0, run.exitCode(), run.output());
        assertFalse(run.output().contains("GATE FAILED"), run.output());
    }

    private static AnalyserCli cliWith(String... args) {
        AnalyserCli cli = new AnalyserCli();
        new CommandLine(cli).parseArgs(args);
        return cli;
    }
}
