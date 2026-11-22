package io.github.rohithv16.aem.analyser.maven;

import io.github.rohithv16.aem.analyser.core.api.AemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Issue #47: {@code aem-analyser:diff} — a delta gate that owns no repository state.
 *
 * <p>Compares this run with a previous run's SARIF (or a baseline file) and fails only on findings
 * that are <em>new</em>, so a branch is held to the code it changed instead of to a legacy debt it
 * inherited. Unlike a committed baseline there is nothing to merge: the previous report is owned by
 * CI, so two branches can run the gate at the same time without conflicting.
 *
 * <p>{@code aem-analyser.previousReport} may be omitted, in which case the default SARIF of the last
 * build in this module ({@code target/aem-analyser.sarif}) is used; if that file does not exist the
 * run is the first one and is reported as a pass with a warning. An explicit path that does not
 * exist is a configuration error, not a pass.
 *
 * <p>{@code aem-analyser.currentReport} compares two artefacts without re-running the analysis,
 * which is what a merge queue wants: it already has both reports.
 */
@Mojo(name = "diff",
        aggregator = true,
        defaultPhase = LifecyclePhase.VERIFY,
        threadSafe = true,
        requiresDependencyResolution = ResolutionScope.NONE)
public class DiffMojo extends AbstractAnalyserMojo {

    /** Accepted {@code aem-analyser.failOnNew} values, in the order users should read them. */
    static final String SUPPORTED_FAIL_ON_NEW = "NONE, ERROR, WARNING, INFO";

    private static final Set<String> KNOWN_THRESHOLDS = Set.of("NONE", "ERROR", "WARNING", "INFO");

    /** Previous run's report: SARIF or baseline JSON. */
    @Parameter(property = "aem-analyser.previousReport")
    private File previousReport;

    /** Compare two artefacts instead of re-running the analysis (SARIF or baseline JSON). */
    @Parameter(property = "aem-analyser.currentReport")
    private File currentReport;

    /** Fail when a new finding is at or above this severity: NONE, ERROR, WARNING or INFO. */
    @Parameter(property = "aem-analyser.failOnNew", defaultValue = "ERROR")
    private String failOnNew;

    /** Where to write the machine-readable delta (default: {@code target/aem-analyser/diff.json}). */
    @Parameter(property = "aem-analyser.diffOutput")
    private File diffOutput;

    @Override
    protected String goalName() {
        return "diff";
    }

    @Override
    protected String defaultFormats() {
        return "SARIF";
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipExecution()) {
            return;
        }
        Severity gate = normaliseThreshold(failOnNew, "aem-analyser.failOnNew");
        Path root = resolveRoot();
        Path previousPath = resolvePrevious(root);
        if (!Files.isRegularFile(previousPath)) {
            if (previousReport != null) {
                throw new MojoExecutionException("aem-analyser.previousReport points at a file that does not"
                        + " exist: " + previousPath);
            }
            // First run in this module: there is no delta to gate on. Say so loudly instead of
            // reporting a green delta that was never computed.
            String message = "AEM Code Analyser: no previous report at " + previousPath
                    + "; treating this run as the baseline (the diff gate does not apply yet).";
            getLog().warn(message);
            writeDiff(ReportDiff.compute(List.of(), List.of()), previousPath, root);
            return;
        }

        List<FindingSet.Finding> previous;
        try {
            previous = FindingSet.read(previousPath);
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to read aem-analyser.previousReport " + previousPath
                    + ": " + e.getMessage(), e);
        }

        List<FindingSet.Finding> current = currentFindings(root);
        ReportDiff.Result result = ReportDiff.compute(previous, current);
        for (String line : ReportDiff.renderTable(result)) {
            getLog().info(line);
        }
        for (String line : ReportDiff.renderFindings("New findings:", result.added())) {
            getLog().info(line);
        }
        for (String line : ReportDiff.renderFindings("Resolved findings:", result.resolved())) {
            getLog().info(line);
        }
        Path written = writeDiff(result, previousPath, root);

        int failing = result.addedAtOrAbove(gate);
        if (failing > 0) {
            throw new MojoFailureException("AEM Code Analyser delta gate failed: " + failing
                    + " new finding(s) at or above severity " + gate + " (of " + result.added().size()
                    + " new, " + result.resolved().size() + " resolved). See " + written + ".");
        }
        if (gate == null) {
            getLog().info("aem-analyser.failOnNew=NONE: report-only delta; " + result.added().size()
                    + " new finding(s) recorded, none of them fail the build.");
        } else {
            getLog().info("AEM Code Analyser delta gate passed: no new findings at or above severity "
                    + gate + " (" + result.added().size() + " new below the threshold).");
        }
    }

    private Path resolvePrevious(Path root) {
        if (previousReport != null) {
            return previousReport.toPath().toAbsolutePath().normalize();
        }
        return defaultOutputDirectory(root).resolve("aem-analyser.sarif");
    }

    /** This run's findings: from an explicit current report when given, otherwise from a live run. */
    private List<FindingSet.Finding> currentFindings(Path root) throws MojoExecutionException {
        if (currentReport != null) {
            Path path = currentReport.toPath().toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new MojoExecutionException("aem-analyser.currentReport points at a file that does not"
                        + " exist: " + path);
            }
            try {
                return FindingSet.read(path);
            } catch (IOException e) {
                throw new MojoExecutionException("Unable to read aem-analyser.currentReport " + path + ": "
                        + e.getMessage(), e);
            }
        }
        getLog().info("AEM Code Analyser: analysing " + root);
        try {
            AemCodeAnalyser analyser = new DefaultAemCodeAnalyser();
            AnalysisReport report = analyser.analyze(buildContext(root));
            // Accepted findings (baseline / inline directives) are not gate-visible, so they are not
            // part of the delta either: reopening one later must not read as "new".
            return FindingSet.fromViolations(report.violations().stream()
                    .filter(violation -> !violation.isAccepted())
                    .toList());
        } catch (Exception e) {
            throw new MojoExecutionException("AEM Code Analyser failed: " + e.getMessage(), e);
        }
    }

    private Path writeDiff(ReportDiff.Result result, Path previousPath, Path root) throws MojoExecutionException {
        Path target = diffOutput != null
                ? diffOutput.toPath().toAbsolutePath().normalize()
                : defaultOutputDirectory(root).resolve("aem-analyser").resolve("diff.json");
        String current = currentReport != null
                ? currentReport.toPath().toAbsolutePath().normalize().toString()
                : root + " (live analysis)";
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, ReportDiff.toJson(result, previousPath.toString(), current),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to write the diff document: " + e.getMessage(), e);
        }
        getLog().info("Wrote diff to " + target);
        return target;
    }

    /**
     * Upper-cases and validates a threshold.
     *
     * @return the canonical severity, or {@code null} for {@code NONE} (report-only) or a blank value
     * @throws MojoExecutionException when the value is not a supported severity
     */
    static Severity normaliseThreshold(String raw, String parameter) throws MojoExecutionException {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String candidate = raw.trim().toUpperCase(Locale.ROOT);
        if (!KNOWN_THRESHOLDS.contains(candidate)) {
            throw new MojoExecutionException(parameter + "=" + raw.trim()
                    + " is not a supported severity. Supported values: " + SUPPORTED_FAIL_ON_NEW + ".");
        }
        return switch (candidate) {
            case "ERROR" -> Severity.ERROR;
            case "WARNING" -> Severity.WARNING;
            case "INFO" -> Severity.INFO;
            default -> null;
        };
    }
}
