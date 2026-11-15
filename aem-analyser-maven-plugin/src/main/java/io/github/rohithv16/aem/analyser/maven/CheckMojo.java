package io.github.rohithv16.aem.analyser.maven;

import io.github.rohithv16.aem.analyser.core.api.AemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.export.ReportFormat;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Path;
import java.util.List;

/**
 * FEAT-DELIV-01: {@code aem-analyser:check} — build-breaking quality gate.
 * Runs the cross-boundary analysis against the whole reactor, writes the configured reports and
 * fails the build when violations exceed the configured thresholds.
 */
@Mojo(name = "check",
        aggregator = true,
        defaultPhase = LifecyclePhase.VERIFY,
        threadSafe = false,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.NONE)
public class CheckMojo extends AbstractAnalyserMojo {

    @Parameter(property = "aem-analyser.failOnError", defaultValue = "true")
    private boolean failOnError;

    @Parameter(property = "aem-analyser.failOnWarning", defaultValue = "false")
    private boolean failOnWarning;

    @Override
    protected String goalName() {
        return "check";
    }

    @Override
    protected String defaultFormats() {
        return "CONSOLE";
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipExecution()) {
            return;
        }
        Path root = resolveRoot();
        List<ReportFormat> reportFormats = resolvedFormats();
        getLog().info("AEM Code Analyser: analysing " + root);

        AnalysisReport report;
        try {
            AemCodeAnalyser analyser = new DefaultAemCodeAnalyser();
            report = analyser.analyze(buildContext(root));
        } catch (Exception e) {
            throw new MojoExecutionException("AEM Code Analyser failed: " + e.getMessage(), e);
        }

        // Write artifacts before failing the build so CI can upload them.
        report = applyFixes(root, report);
        report = activeConfig.apply(report);
        report = applyBaseline(root, report);
        report = applyHistory(root, report);
        writeReports(report, root, reportFormats);
        logDiagnostics(report);

        long errors = report.countBySeverity(Severity.ERROR);
        long warnings = report.countBySeverity(Severity.WARNING);
        getLog().info(String.format("AEM Code Analyser: %d violation(s) (%d errors, %d warnings) in %.3f s",
                report.violations().size(), errors, warnings, report.durationMillis() / 1000.0));

        if (failOnError && errors > 0) {
            throw new MojoFailureException(
                    "AEM Code Analyser detected " + errors + " cross-boundary error(s) (AEM-DEAD rules).");
        }
        if (failOnWarning && warnings > 0) {
            throw new MojoFailureException(
                    "AEM Code Analyser detected " + warnings + " cross-boundary warning(s).");
        }
        if (failOn != null && !failOn.isBlank()) {
            String threshold = failOn.trim().toUpperCase(java.util.Locale.ROOT);
            long infos = report.countBySeverity(Severity.INFO);
            boolean failed = ("ERROR".equals(threshold) && errors > 0)
                    || ("WARNING".equals(threshold) && (errors > 0 || warnings > 0))
                    || ("INFO".equals(threshold) && (errors > 0 || warnings > 0 || infos > 0));
            if (failed) {
                throw new MojoFailureException(
                        "AEM Code Analyser detected findings at or above severity " + threshold + ".");
            }
        }
        if (minCoverage != null) {
            var coverage = report.coverageReport();
            if (coverage == null || !coverage.present()
                    || coverage.lineCoveragePercent() < minCoverage) {
                throw new MojoFailureException("AEM Code Analyser quality gate failed: line coverage "
                        + (coverage == null || !coverage.present()
                        ? "not detected" : String.format("%.1f%%", coverage.lineCoveragePercent()))
                        + " is below " + minCoverage + "%.");
            }
        }
        if (maxDuplication != null) {
            double duplication = report.codeMetrics() == null
                    ? 0.0 : report.codeMetrics().duplicationPercent();
            if (duplication > maxDuplication) {
                throw new MojoFailureException(String.format(
                        "AEM Code Analyser quality gate failed: duplication %.1f%% exceeds %.1f%%.",
                        duplication, maxDuplication));
            }
        }
        if (maxWarnings != null && warnings > maxWarnings) {
            throw new MojoFailureException("AEM Code Analyser quality gate failed: " + warnings
                    + " warnings exceed " + maxWarnings + ".");
        }
    }
}
