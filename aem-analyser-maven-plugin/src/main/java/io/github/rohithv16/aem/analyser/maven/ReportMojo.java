package io.github.rohithv16.aem.analyser.maven;

import io.github.rohithv16.aem.analyser.core.api.AemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.export.ReportFormat;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

import java.nio.file.Path;
import java.util.List;

/**
 * FEAT-DELIV-01: {@code aem-analyser:report} — standalone report generation.
 * Runs the cross-boundary analysis and writes the configured export formats
 * (SARIF, SonarQube Generic Issue JSON, ANSI console text, HTML) without failing the build.
 */
@Mojo(name = "report", aggregator = true, defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class ReportMojo extends AbstractAnalyserMojo {

    @Override
    protected String goalName() {
        return "report";
    }

    @Override
    protected String defaultFormats() {
        return "SARIF,SONAR";
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipExecution()) {
            return;
        }
        Path root = resolveRoot();
        List<ReportFormat> reportFormats = resolvedFormats();
        getLog().info("AEM Code Analyser: generating reports for " + root);

        AnalysisReport report;
        try {
            AemCodeAnalyser analyser = new DefaultAemCodeAnalyser();
            report = analyser.analyze(buildContext(root));
        } catch (Exception e) {
            throw new MojoExecutionException("AEM Code Analyser failed: " + e.getMessage(), e);
        }

        report = applyFixes(root, report);
        report = activeConfig.apply(report);
        report = applyBaseline(root, report);
        report = applyHistory(root, report);
        writeReports(report, root, reportFormats);
        logDiagnostics(report);
        getLog().info("AEM Code Analyser: " + report.violations().size() + " violation(s) in "
                + String.format("%.3f s", report.durationMillis() / 1000.0));
    }
}
