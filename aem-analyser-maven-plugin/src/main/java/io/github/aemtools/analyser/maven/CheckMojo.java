package io.github.aemtools.analyser.maven;

import io.github.aemtools.analyser.core.api.AnalysisContext;
import io.github.aemtools.analyser.core.api.AemCodeAnalyser;
import io.github.aemtools.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.aemtools.analyser.core.model.report.AnalysisReport;
import io.github.aemtools.analyser.core.model.report.Severity;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Path;

/**
 * FEAT-DELIV-01: {@code aem-analyser:check} — build-breaking quality gate.
 * Runs the cross-boundary analysis against the whole reactor and fails the build
 * when violations exceed the configured thresholds.
 */
@Mojo(name = "check",
        aggregator = true,
        defaultPhase = LifecyclePhase.VERIFY,
        threadSafe = false,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.NONE)
public class CheckMojo extends AbstractMojo {

    /** Project root to analyse; defaults to the Maven session execution root. */
    @Parameter(defaultValue = "${session.executionRootDirectory}", readonly = true)
    private File executionRoot;

    /** Explicit project root override. */
    @Parameter(property = "aem-analyser.projectRoot")
    private File projectRoot;

    @Parameter(property = "aem-analyser.failOnError", defaultValue = "true")
    private boolean failOnError;

    @Parameter(property = "aem-analyser.failOnWarning", defaultValue = "false")
    private boolean failOnWarning;

    @Parameter(property = "aem-analyser.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("aem-analyser:check skipped (aem-analyser.skip=true)");
            return;
        }
        // Run once per reactor at the execution root
        if (project != null && session != null
                && !session.getExecutionRootDirectory().equals(project.getBasedir().getAbsolutePath())) {
            getLog().debug("aem-analyser:check runs only at the reactor root; skipping module "
                    + project.getArtifactId());
            return;
        }

        Path root = (projectRoot != null ? projectRoot.toPath() : executionRoot.toPath()).toAbsolutePath();

        getLog().info("AEM Code Analyser: analysing " + root);

        AnalysisReport report;
        try {
            AemCodeAnalyser analyser = new DefaultAemCodeAnalyser();
            report = analyser.analyze(AnalysisContext.of(root));
        } catch (Exception e) {
            throw new MojoExecutionException("AEM Code Analyser failed: " + e.getMessage(), e);
        }

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
    }
}
