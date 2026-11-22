package io.github.rohithv16.aem.analyser.maven;

import io.github.rohithv16.aem.analyser.core.api.AemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.export.ReportFormat;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * FEAT-DELIV-01: {@code aem-analyser:check} — build-breaking quality gate.
 * Runs the cross-boundary analysis against the whole reactor, writes the configured reports and
 * fails the build when violations exceed the configured thresholds.
 *
 * <p>{@code aem-analyser.failOn} accepts the same value space as the CLI's {@code --fail-on}:
 * {@code NONE}, {@code ERROR}, {@code WARNING} and {@code INFO}. {@code NONE} is report-only: it
 * suppresses the severity gates ({@code failOnError}, {@code failOnWarning} and {@code failOn}) but
 * not the numeric quality-gate thresholds, exactly as {@code --fail-on NONE} behaves in the CLI.
 * Any other value is rejected instead of being silently ignored.
 */
@Mojo(name = "check",
        aggregator = true,
        defaultPhase = LifecyclePhase.VERIFY,
        threadSafe = true,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.NONE)
public class CheckMojo extends AbstractAnalyserMojo {

    /** Accepted {@code aem-analyser.failOn} values, listed in the order users should read them. */
    static final String SUPPORTED_FAIL_ON = "NONE, ERROR, WARNING, INFO";

    private static final Set<String> KNOWN_FAIL_ON = Set.of("NONE", "ERROR", "WARNING", "INFO");

    /** How many distinct rules a failure message names before eliding the rest. */
    private static final int MAX_RULES_IN_MESSAGE = 8;

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
        // Fail fast on a typo in the threshold instead of silently keeping the implicit gate.
        String threshold = normaliseFailOn(failOn);
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

        if ("NONE".equals(threshold)) {
            // Report-only run: the severity gates below stay silent, the thresholds still gate.
            getLog().info("aem-analyser.failOn=NONE: report-only run; not failing on " + errors
                    + " error-severity and " + warnings + " warning-severity finding(s)."
                    + " minCoverage, maxDuplication and maxWarnings still apply.");
        } else {
            Optional<String> failure = severityFailure(threshold, failOnError, failOnWarning, report);
            if (failure.isPresent()) {
                throw new MojoFailureException(failure.get());
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

    /**
     * Upper-cases and validates {@code aem-analyser.failOn}.
     *
     * @return the canonical value, or {@code null} when the parameter is unset or blank
     * @throws MojoExecutionException when the value is not in {@link #SUPPORTED_FAIL_ON}
     */
    static String normaliseFailOn(String raw) throws MojoExecutionException {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String candidate = raw.trim().toUpperCase(Locale.ROOT);
        if (!KNOWN_FAIL_ON.contains(candidate)) {
            throw new MojoExecutionException("aem-analyser.failOn=" + raw.trim()
                    + " is not a supported severity. Supported values: " + SUPPORTED_FAIL_ON + ".");
        }
        return candidate;
    }

    /**
     * Builds the severity-gate failure message, or empty when this gate lets the build pass.
     *
     * <p>Precedence matches the CLI: an explicit {@code failOn} threshold replaces the implicit
     * default gate; without it, {@code failOnError} (default {@code true}) gates on ERROR and
     * {@code failOnWarning} gates on WARNING. {@code NONE} never fails here.
     *
     * @param threshold     canonical value from {@link #normaliseFailOn(String)}, may be null
     * @param failOnError   the {@code aem-analyser.failOnError} switch
     * @param failOnWarning the {@code aem-analyser.failOnWarning} switch
     * @param report        the analysed report
     * @return the message naming the severity and the offending rule ids, or empty
     */
    static Optional<String> severityFailure(String threshold, boolean failOnError, boolean failOnWarning,
                                            AnalysisReport report) {
        Severity gate = gatingSeverity(threshold, failOnError, failOnWarning);
        if (gate == null) {
            return Optional.empty();
        }
        List<Violation> failing = report.violations().stream()
                .filter(violation -> violation.severity() != null && atOrAbove(violation.severity(), gate))
                .toList();
        if (failing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("AEM Code Analyser detected " + failing.size() + " finding(s) at or above severity "
                + gate + " (rules: " + ruleIds(failing) + ").");
    }

    private static Severity gatingSeverity(String threshold, boolean failOnError, boolean failOnWarning) {
        if ("NONE".equals(threshold)) {
            // Report-only: NONE suppresses the implicit gates as well as the explicit threshold.
            return null;
        }
        Severity gate = switch (threshold == null ? "" : threshold) {
            case "ERROR" -> Severity.ERROR;
            case "WARNING" -> Severity.WARNING;
            case "INFO" -> Severity.INFO;
            default -> null; // unset (normaliseFailOn rejects anything else)
        };
        // Each active switch is a gate of its own; the union of the gates is the least severe of
        // them, because "any finding at or above WARNING" already covers "at or above ERROR".
        if (failOnError) {
            gate = leastSevere(gate, Severity.ERROR);
        }
        if (failOnWarning) {
            gate = leastSevere(gate, Severity.WARNING);
        }
        return gate;
    }

    private static Severity leastSevere(Severity left, Severity right) {
        if (left == null) {
            return right;
        }
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    /** Severity is ordered ERROR, WARNING, INFO, so "at or above" means "at most". */
    private static boolean atOrAbove(Severity severity, Severity gate) {
        return severity.ordinal() <= gate.ordinal();
    }

    private static String ruleIds(List<Violation> failing) {
        TreeSet<String> ids = new TreeSet<>();
        for (Violation violation : failing) {
            ids.add(violation.ruleId() == null ? "unknown" : violation.ruleId());
        }
        List<String> ordered = List.copyOf(ids);
        if (ordered.size() <= MAX_RULES_IN_MESSAGE) {
            return String.join(", ", ordered);
        }
        return String.join(", ", ordered.subList(0, MAX_RULES_IN_MESSAGE))
                + ", +" + (ordered.size() - MAX_RULES_IN_MESSAGE) + " more";
    }
}
