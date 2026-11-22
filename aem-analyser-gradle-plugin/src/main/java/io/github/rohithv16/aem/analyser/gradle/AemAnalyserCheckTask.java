package io.github.rohithv16.aem.analyser.gradle;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import io.github.rohithv16.aem.analyser.core.api.AnalysisContext;
import io.github.rohithv16.aem.analyser.core.api.AnalysisException;
import io.github.rohithv16.aem.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.export.ExportException;
import io.github.rohithv16.aem.analyser.core.export.SarifReportExporter;
import io.github.rohithv16.aem.analyser.core.export.TextReportExporter;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

/**
 * Analyses the project in-process and writes SARIF plus a console-shaped text report.
 *
 * <p>Declared inputs are the project tree (relative-path sensitive, so moving the checkout does not
 * invalidate the cache) and the three gate knobs. Declared outputs are the report files. With no
 * change to either, Gradle reports {@code UP-TO-DATE} or replays the entry from the build cache.</p>
 */
@CacheableTask
public abstract class AemAnalyserCheckTask extends DefaultTask {

    /**
     * Every file the analysis reads, relative-path sensitive so moving the checkout does not
     * invalidate the cache. The build directory is excluded by the plugin: reports live inside the
     * project tree, and a task whose output is part of its input is never {@code UP-TO-DATE}.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract org.gradle.api.file.ConfigurableFileCollection getProjectFiles();

    /** The reactor root: one analysis over every module, which is the point of the tool. */
    @org.gradle.api.tasks.Internal
    public abstract org.gradle.api.file.DirectoryProperty getProjectDirectory();

    /** Where {@code aem-analyser.sarif} and {@code aem-analyser.txt} are written. */
    @OutputDirectory
    public abstract org.gradle.api.file.DirectoryProperty getOutputDirectory();

    @Input
    public abstract Property<String> getFailOn();

    @Input
    public abstract Property<Boolean> getEnableSonar();

    @Input
    public abstract Property<Integer> getMaxWarnings();

    @TaskAction
    public void analyse() {
        Path root = getProjectDirectory().get().getAsFile().toPath();
        Path output = getOutputDirectory().get().getAsFile().toPath();
        String failOn = getFailOn().get().trim().toUpperCase(java.util.Locale.ROOT);

        AnalysisReport report;
        try {
            Files.createDirectories(output);
            report = new DefaultAemCodeAnalyser().analyze(AnalysisContext.builder(root)
                    .enableSonar(getEnableSonar().get())
                    .build());
            write(output.resolve("aem-analyser.sarif"), new SarifReportExporter(), report);
            write(output.resolve("aem-analyser.txt"), new TextReportExporter(), report);
        } catch (AnalysisException | ExportException | IOException e) {
            throw new GradleException("AEM Code Analyser could not complete: " + e.getMessage(), e);
        }

        long errors = count(report, Severity.ERROR);
        long warnings = count(report, Severity.WARNING);
        long infos = count(report, Severity.INFO);
        getLogger().lifecycle("AEM Code Analyser: {} findings ({} error, {} warning, {} info) in {} ms",
                report.violations().size(), errors, warnings, infos, report.durationMillis());

        int maxWarnings = getMaxWarnings().get();
        if (maxWarnings >= 0 && warnings > maxWarnings) {
            throw new GradleException("AEM Code Analyser: " + warnings + " warnings exceeds the "
                    + "configured maximum of " + maxWarnings + ". Reports: " + output);
        }

        Severity threshold = threshold(failOn);
        if (threshold == null) {
            return;
        }
        long failing = report.violations().stream()
                .filter(violation -> violation.severity().ordinal() <= threshold.ordinal())
                .count();
        if (failing > 0) {
            throw new GradleException("AEM Code Analyser: " + failing + " finding(s) at or above "
                    + threshold + " (failOn = " + threshold + "). Reports: " + output);
        }
    }

    private static Severity threshold(String failOn) {
        return switch (failOn) {
            case "NONE" -> null;
            case "ERROR" -> Severity.ERROR;
            case "WARNING" -> Severity.WARNING;
            case "INFO" -> Severity.INFO;
            default -> throw new GradleException("aemAnalyser.failOn must be NONE, ERROR, WARNING or INFO"
                    + " but was '" + failOn + "'.");
        };
    }

    private static long count(AnalysisReport report, Severity severity) {
        List<Violation> violations = report.violations();
        return violations.stream().filter(violation -> violation.severity() == severity).count();
    }

    private static void write(Path target, io.github.rohithv16.aem.analyser.core.export.ReportExporter exporter,
                              AnalysisReport report) throws ExportException, IOException {
        try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            exporter.export(report, writer);
        }
    }
}
