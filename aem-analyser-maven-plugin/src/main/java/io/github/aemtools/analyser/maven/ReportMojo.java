package io.github.aemtools.analyser.maven;

import io.github.aemtools.analyser.core.api.AnalysisContext;
import io.github.aemtools.analyser.core.api.AemCodeAnalyser;
import io.github.aemtools.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.aemtools.analyser.core.model.report.AnalysisReport;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * FEAT-DELIV-01: {@code aem-analyser:report} — standalone report generation.
 * Runs the cross-boundary analysis and writes the configured export formats
 * (SARIF, SonarQube Generic Issue JSON, ANSI console text) to the build directory.
 * Never fails the build.
 */
@Mojo(name = "report", aggregator = true, defaultPhase = LifecyclePhase.VERIFY)
public class ReportMojo extends AbstractMojo {

    @Parameter(defaultValue = "${session.executionRootDirectory}", readonly = true)
    private File executionRoot;

    @Parameter(property = "aem-analyser.projectRoot")
    private File projectRoot;

    /** Export formats to generate: SARIF, SONAR, CONSOLE (comma-separated). */
    @Parameter(property = "aem-analyser.formats", defaultValue = "SARIF,SONAR")
    private String formats;

    @Parameter(property = "aem-analyser.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("aem-analyser:report skipped (aem-analyser.skip=true)");
            return;
        }
        if (project != null && session != null
                && !session.getExecutionRootDirectory().equals(project.getBasedir().getAbsolutePath())) {
            getLog().debug("aem-analyser:report runs only at the reactor root; skipping module "
                    + project.getArtifactId());
            return;
        }

        Path root = (projectRoot != null ? projectRoot.toPath() : executionRoot.toPath()).toAbsolutePath();
        getLog().info("AEM Code Analyser: generating reports for " + root);

        AnalysisReport report;
        try {
            AemCodeAnalyser analyser = new DefaultAemCodeAnalyser();
            report = analyser.analyze(AnalysisContext.of(root));
        } catch (Exception e) {
            throw new MojoExecutionException("AEM Code Analyser failed: " + e.getMessage(), e);
        }

        Path outputDirectory = project != null
                ? project.getBuild().getDirectory() != null
                ? Path.of(project.getBuild().getDirectory())
                : root.resolve("target")
                : root.resolve("target");
        try {
            writeReports(report, outputDirectory, List.of(formats.split("\\s*,\\s*")));
            getLog().info("AEM Code Analyser reports written to " + outputDirectory);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write analysis reports: " + e.getMessage(), e);
        }
    }

    private void writeReports(AnalysisReport report, Path outputDirectory, List<String> formats) throws IOException {
        Files.createDirectories(outputDirectory);
        for (String format : formats) {
            io.github.aemtools.analyser.core.export.ReportFormat reportFormat =
                    io.github.aemtools.analyser.core.export.ReportFormat.valueOf(format.toUpperCase(java.util.Locale.ROOT));
            var file = outputDirectory.resolve(switch (reportFormat) {
                case SARIF -> "aem-analyser.sarif";
                case SONAR -> "sonar-aem-analyser-report.json";
                case CONSOLE -> "aem-analyser-report.txt";
            });
            try (var writer = Files.newBufferedWriter(file)) {
                switch (reportFormat) {
                    case SARIF -> new io.github.aemtools.analyser.core.export.SarifReportExporter()
                            .export(report, writer);
                    case SONAR -> new io.github.aemtools.analyser.core.export.SonarReportExporter()
                            .export(report, writer);
                    case CONSOLE -> new io.github.aemtools.analyser.core.export.ConsoleReportFormatter()
                            .export(report, writer);
                }
            } catch (io.github.aemtools.analyser.core.export.ExportException e) {
                throw new IOException("Export failed: " + e.getMessage(), e);
            }
        }
    }
}
