package io.github.rohithv16.aem.analyser.maven;

import io.github.rohithv16.aem.analyser.core.api.AnalysisContext;
import io.github.rohithv16.aem.analyser.core.export.ConsoleReportFormatter;
import io.github.rohithv16.aem.analyser.core.export.ExportException;
import io.github.rohithv16.aem.analyser.core.export.HtmlReportExporter;
import io.github.rohithv16.aem.analyser.core.export.ReportExporter;
import io.github.rohithv16.aem.analyser.core.export.ReportFormat;
import io.github.rohithv16.aem.analyser.core.export.SarifReportExporter;
import io.github.rohithv16.aem.analyser.core.export.SonarReportExporter;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared Maven parameter surface and report-export behaviour for the {@code check} and
 * {@code report} goals.
 *
 * <p>Keeping the parameters here guarantees both goals document and accept the same options:
 * project root, profiles, threads, ignore lists, Sonar toggles, formats and output file.
 */
public abstract class AbstractAnalyserMojo extends AbstractMojo {

    /** Canonical, human-facing list of format names, used by every error message. */
    private static final String SUPPORTED_FORMATS =
            "CONSOLE, TEXT, JSON, SARIF, SONAR, HTML, GITHUB, GITLAB, AZURE, JUNIT";

    /** Explicit project root override. */
    @Parameter(property = "aem-analyser.projectRoot")
    protected File projectRoot;

    /** Comma-separated Maven profiles to activate during discovery. */
    @Parameter(property = "aem-analyser.profiles")
    protected String profiles;

    /** Worker thread count for parallel parsing (0 = available processors - 1). */
    @Parameter(property = "aem-analyser.threads", defaultValue = "0")
    protected int threads;

    /** Skip files larger than this many bytes (default 10 MiB). */
    @Parameter(property = "aem-analyser.maxFileSizeBytes", alias = "maxFileSize", defaultValue = "10485760")
    protected long maxFileSizeBytes;

    /** Overall analysis timeout in seconds (0 = none). */
    @Parameter(property = "aem-analyser.timeoutSeconds", alias = "timeout", defaultValue = "0")
    protected long timeoutSeconds;

    /** Comma-separated service implementation class names never flagged unused. */
    @Parameter(property = "aem-analyser.ignoredServices")
    protected String ignoredServices;

    /** Comma-separated reactor-relative glob patterns of OSGi configs to exclude. */
    @Parameter(property = "aem-analyser.ignoredConfigPaths")
    protected String ignoredConfigPaths;

    /** Comma-separated JAR files or directories to index for external PID suppression. */
    @Parameter(property = "aem-analyser.dependencyJars")
    protected String dependencyJars;

    /** Project configuration YAML (default: .aem-analyser.yml at the project root). */
    @Parameter(property = "aem-analyser.configFile")
    protected File configFile;

    protected io.github.rohithv16.aem.analyser.core.config.AnalyserConfig activeConfig =
            io.github.rohithv16.aem.analyser.core.config.AnalyserConfig.empty();

    /** Enable the embedded SonarJava analysis. */
    @Parameter(property = "aem-analyser.enableSonar", defaultValue = "true")
    protected boolean enableSonar;

    /** SonarJava ruleset filter (default, or comma-separated rule IDs). */
    @Parameter(property = "aem-analyser.sonarRules", defaultValue = "default")
    protected String sonarRules;

    /** Comma-separated report formats: CONSOLE, TEXT, JSON, SARIF, SONAR, HTML, GITHUB, GITLAB, AZURE, JUNIT. */
    @Parameter(property = "aem-analyser.formats", alias = "format")
    protected String formats;

    /**
     * Single output file. When exactly one format is configured the report is written to this
     * file; with several formats it is treated as an output directory.
     */
    @Parameter(property = "aem-analyser.outputFile")
    protected File outputFile;

    /** Skip the goal entirely. */
    @Parameter(property = "aem-analyser.skip", defaultValue = "false")
    protected boolean skip;

    /**
     * Issue #44: additionally write a report per reactor module under
     * {@code target/aem-analyser/modules/<artifactId>/}, plus a module/errors/warnings table.
     * Findings that belong to no module land in the {@code _reactor} bucket.
     */
    @Parameter(property = "aem-analyser.perModule", defaultValue = "false")
    protected boolean perModule;

    /** Print structured analysis diagnostics. */
    @Parameter(property = "aem-analyser.verbose", defaultValue = "false")
    protected boolean verbose;

    /** Generate the HTML dashboard even when it is not listed in {@code formats}. */
    @Parameter(property = "aem-analyser.generateHtml", defaultValue = "true")
    protected boolean generateHtml;

    /** Baseline JSON file of accepted findings. */
    @Parameter(property = "aem-analyser.baseline")
    protected File baseline;

    /** Write current findings to the baseline file and gate only new findings. */
    @Parameter(property = "aem-analyser.updateBaseline", defaultValue = "false")
    protected boolean updateBaseline;

    /** Keep baseline-suppressed findings in reports at INFO severity. */
    @Parameter(property = "aem-analyser.baselineReport", defaultValue = "false")
    protected boolean baselineReport;

    /** Analyse only files changed in Git (fast pre-filter; combine with {@link #since}). */
    @Parameter(property = "aem-analyser.changedOnly", defaultValue = "false")
    protected boolean changedOnly;

    /** Git ref/commit to compare against; implies changed-only. */
    @Parameter(property = "aem-analyser.since")
    protected String since;

    /** Apply safe automated fixes before reporting. */
    @Parameter(property = "aem-analyser.fix", defaultValue = "false")
    protected boolean fix;

    /** Preview automated fixes without writing changes. */
    @Parameter(property = "aem-analyser.fixDryRun", defaultValue = "false")
    protected boolean fixDryRun;

    /** Fail when a finding at or above this severity exists: ERROR, WARNING or INFO. */
    @Parameter(property = "aem-analyser.failOn")
    protected String failOn;

    /** Quality gate: minimum line coverage percentage. */
    @Parameter(property = "aem-analyser.minCoverage")
    protected Double minCoverage;

    /** Quality gate: maximum tolerated duplication percentage. */
    @Parameter(property = "aem-analyser.maxDuplication")
    protected Double maxDuplication;

    /** Quality gate: maximum tolerated warning count. */
    @Parameter(property = "aem-analyser.maxWarnings")
    protected Integer maxWarnings;

    /** Append a run summary to this directory and render the trend in the HTML report. */
    @Parameter(property = "aem-analyser.history")
    protected File historyDirectory;

    /** Number of historical runs shown in the HTML trend. */
    @Parameter(property = "aem-analyser.historyLimit", defaultValue = "20")
    protected int historyLimit;

    protected List<io.github.rohithv16.aem.analyser.core.history.RunSummary> history = List.of();

    @Parameter(defaultValue = "${session.executionRootDirectory}", readonly = true)
    protected File executionRoot;

    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession session;

    protected abstract String goalName();

    protected abstract String defaultFormats();

    /**
     * @return true when this execution should be skipped (skip flag or non-root reactor module)
     */
    protected boolean skipExecution() {
        if (skip) {
            getLog().info("aem-analyser:" + goalName() + " skipped (aem-analyser.skip=true)");
            return true;
        }
        if (project != null && session != null
                && !session.getExecutionRootDirectory().equals(project.getBasedir().getAbsolutePath())) {
            // Never silent: a scoped build (-pl) used to produce an empty, green run with no hint
            // that nothing had been analysed.
            getLog().info("aem-analyser:" + goalName() + " runs only at the reactor root; skipping module '"
                    + project.getArtifactId() + "'. Run the goal at the reactor root (or pass -Daem-analyser.projectRoot) for a full analysis.");
            return true;
        }
        return false;
    }

    protected Path resolveRoot() {
        return (projectRoot != null ? projectRoot.toPath() : executionRoot.toPath())
                .toAbsolutePath().normalize();
    }

    protected AnalysisContext buildContext(Path root) {
        AnalysisContext.Builder builder = AnalysisContext.builder(root)
                .enableSonar(enableSonar)
                .sonarRules(sonarRules)
                .maxFileSizeBytes(maxFileSizeBytes)
                .timeoutMillis(Math.max(0, timeoutSeconds) * 1000L);
        try {
            activeConfig = configFile != null
                    ? io.github.rohithv16.aem.analyser.core.config.AnalyserConfigLoader
                            .load(configFile.toPath())
                    : io.github.rohithv16.aem.analyser.core.config.AnalyserConfigLoader.loadIfPresent(root);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not read configuration: " + e.getMessage(), e);
        }
        if (profiles != null && !profiles.isBlank()) {
            builder = builder.activeProfiles(splitCsv(profiles));
        }
        if (threads > 0) {
            builder = builder.threads(threads);
        }
        Set<String> services = new java.util.LinkedHashSet<>(splitCsvSet(ignoredServices));
        services.addAll(activeConfig.allowlists().services());
        if (!services.isEmpty()) {
            builder = builder.ignoredServices(services);
        }
        Set<String> configs = new java.util.LinkedHashSet<>(splitCsvSet(ignoredConfigPaths));
        configs.addAll(activeConfig.allowlists().configPaths());
        if (!configs.isEmpty()) {
            builder = builder.ignoredConfigPaths(configs);
        }
        Set<String> jars = splitCsvSet(dependencyJars);
        if (!jars.isEmpty()) {
            builder = builder.dependencyJarRoots(jars.stream().map(Path::of).toList());
        }
        if (changedOnly || (since != null && !since.isBlank())) {
            var diff = new io.github.rohithv16.aem.analyser.core.git.GitDiffResolver()
                    .resolveChanged(root, since);
            if (diff.isGitRepo()) {
                builder = builder.changedFiles(diff.changedFiles());
            }
        }
        return builder.build();
    }

    /**
     * Parses the configured formats, validating them eagerly so users get a clear build error
     * instead of a raw {@link IllegalArgumentException} stack trace.
     */
    protected List<ReportFormat> resolvedFormats() throws MojoFailureException {
        String spec = (formats == null || formats.isBlank()) ? defaultFormats() : formats;
        List<ReportFormat> result = new ArrayList<>();
        for (String token : spec.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result.add(ReportFormat.valueOf(trimmed.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new MojoFailureException("Unknown report format '" + trimmed
                        + "'. Supported formats: " + SUPPORTED_FORMATS + ".");
            }
        }
        if (result.isEmpty()) {
            throw new MojoFailureException("No report format configured. "
                    + "Supported formats: " + SUPPORTED_FORMATS + ".");
        }
        return result;
    }

    protected AnalysisReport applyBaseline(Path root, AnalysisReport report) throws MojoExecutionException {
        Path path = baseline != null ? baseline.toPath().toAbsolutePath().normalize() : null;
        Path defaultPath = root.resolve(".aem-analyser-baseline.json");
        try {
            if (updateBaseline) {
                if (path == null) {
                    path = defaultPath;
                }
                io.github.rohithv16.aem.analyser.core.baseline.BaselineStore.save(path, report);
                getLog().info("AEM Code Analyser: baseline written to " + path + " ("
                        + report.violations().size() + " finding(s) accepted).");
            } else if (path == null && java.nio.file.Files.isRegularFile(defaultPath)) {
                path = defaultPath;
            }
            if (path == null) {
                return report;
            }
            if (!java.nio.file.Files.isRegularFile(path)) {
                if (!updateBaseline) {
                    getLog().warn("AEM Code Analyser: baseline file not found: " + path);
                }
                return report;
            }
            var loaded = io.github.rohithv16.aem.analyser.core.baseline.BaselineStore.load(path);
            int suppressed = (int) report.violations().stream()
                    .filter(v -> loaded.contains(io.github.rohithv16.aem.analyser.core.baseline.Fingerprint.of(v)))
                    .count();
            AnalysisReport filtered = io.github.rohithv16.aem.analyser.core.baseline.BaselineFilter
                    .filter(report, loaded, baselineReport);
            getLog().info("AEM Code Analyser: baseline suppressed " + suppressed + " accepted finding(s).");
            return filtered;
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to apply baseline: " + e.getMessage(), e);
        }
    }

    protected AnalysisReport applyFixes(Path root, AnalysisReport report) throws MojoExecutionException {
        if (!fix && !fixDryRun) {
            return report;
        }
        var summary = new io.github.rohithv16.aem.analyser.core.fix.AutoFixEngine()
                .fix(root, report.violations(), fixDryRun);
        if (summary.hasFixes()) {
            getLog().info("AEM Code Analyser: " + (fixDryRun ? "would repair " : "repaired ")
                    + summary.fixesApplied() + " issue(s) across " + summary.filesModified() + " file(s).");
            for (var record : summary.records()) {
                getLog().info("  - [" + record.ruleId() + "] " + record.filePath() + ": " + record.description());
            }
            if (!fixDryRun) {
                try {
                    return new io.github.rohithv16.aem.analyser.core.api.DefaultAemCodeAnalyser()
                            .analyze(buildContext(root));
                } catch (Exception e) {
                    throw new MojoExecutionException("Re-analysis after auto-fix failed: " + e.getMessage(), e);
                }
            }
        } else {
            getLog().info("AEM Code Analyser: no auto-fixable issues detected.");
        }
        return report;
    }

    protected AnalysisReport applyHistory(Path root, AnalysisReport report) throws MojoExecutionException {
        if (historyDirectory == null) {
            return report;
        }
        try {
            io.github.rohithv16.aem.analyser.core.history.HistoryStore
                    .append(historyDirectory.toPath(), report);
            history = io.github.rohithv16.aem.analyser.core.history.HistoryStore
                    .load(historyDirectory.toPath(), historyLimit);
            return report;
        } catch (java.io.IOException e) {
            throw new MojoExecutionException("Unable to write run history: " + e.getMessage(), e);
        }
    }

    protected void logDiagnostics(AnalysisReport report) {
        if (!verbose || report.diagnostics().isEmpty()) {
            return;
        }
        getLog().info("AEM Code Analyser: " + report.diagnostics().size() + " diagnostic(s):");
        for (var diagnostic : report.diagnostics()) {
            String location = diagnostic.line() > 0
                    ? diagnostic.path() + ":" + diagnostic.line()
                    : diagnostic.path();
            getLog().info("  - [" + diagnostic.kind() + "] " + location + ": " + diagnostic.reason());
        }
    }

    protected void writeReports(AnalysisReport report, Path root, List<ReportFormat> formats)
            throws MojoExecutionException {
        Path directory;
        Path singleFile = null;
        if (outputFile != null) {
            Path output = outputFile.toPath().toAbsolutePath().normalize();
            if (formats.size() == 1) {
                // An explicit file parameter with exactly one format: the file IS the report.
                singleFile = output;
                directory = output.getParent();
            } else {
                directory = output;
            }
        } else {
            // No outputFile: the default target directory is always a DIRECTORY. Treating it as the
            // report file wrote a plain file named "target" over the build directory and broke every
            // later Maven goal in that module.
            directory = defaultOutputDirectory(root);
        }

        try {
            Files.createDirectories(directory);
            for (ReportFormat format : formats) {
                Path file = singleFile != null ? singleFile : directory.resolve(fileName(format));
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    exporter(format).export(report, writer);
                }
                getLog().info("Wrote " + format + " report to " + file);
            }

            if (generateHtml && !formats.contains(ReportFormat.HTML)) {
                Path htmlFile = (singleFile != null ? singleFile.getParent() : directory)
                        .resolve("aem-analyser-report.html");
                try (Writer writer = Files.newBufferedWriter(htmlFile, StandardCharsets.UTF_8)) {
                    new HtmlReportExporter(java.time.Clock.systemDefaultZone(), 5000, history)
                            .export(report, writer);
                }
                getLog().info("Wrote HTML report to " + htmlFile);
            }

            if (perModule) {
                writePerModuleReports(report, root, formats);
            }
        } catch (IOException | ExportException e) {
            throw new MojoExecutionException("Failed to write analysis reports: " + e.getMessage(), e);
        }
    }

    protected Path defaultOutputDirectory(Path root) {
        if (project != null && project.getBuild() != null && project.getBuild().getDirectory() != null) {
            return Path.of(project.getBuild().getDirectory());
        }
        return root.resolve("target");
    }

    /**
     * Issue #44: builds the module attributor from the live Maven session.
     *
     * <p>Modules whose base directory is outside the analysed tree are dropped — they cannot own any
     * of its files, and a multi-root session ({@code -pl} from a sibling checkout) must not invent
     * buckets for code that was never scanned.
     */
    protected ModuleAttributor moduleAttributor(Path root) {
        List<ModuleAttributor.Module> modules = new ArrayList<>();
        if (session != null && session.getProjects() != null) {
            for (MavenProject reactorProject : session.getProjects()) {
                if (reactorProject.getBasedir() == null) {
                    continue;
                }
                String relative = ModuleAttributor.relativePathOf(root, reactorProject.getBasedir().toPath());
                if (relative == null) {
                    continue;
                }
                modules.add(new ModuleAttributor.Module(reactorProject.getArtifactId(), relative));
            }
        }
        if (modules.isEmpty() && project != null && project.getBasedir() != null) {
            modules.add(new ModuleAttributor.Module(project.getArtifactId(), ""));
        }
        return ModuleAttributor.of(modules);
    }

    /**
     * Issue #44: writes one report set per reactor module under
     * {@code <build directory>/aem-analyser/modules/<artifactId>/} and logs the module table.
     *
     * <p>Each subset reuses the exporters of the main report, so a module's SARIF is the same
     * document shape CI already ingests — only the findings differ. Accepted findings are carried
     * into the owning module too, so the per-module SARIF tells the same "exists, accepted" story as
     * the reactor-wide one. Findings no module claims go to {@code _reactor}: nothing vanishes.
     */
    protected void writePerModuleReports(AnalysisReport report, Path root, List<ReportFormat> formats)
            throws MojoExecutionException {
        ModuleAttributor attributor = moduleAttributor(root);
        Map<String, List<io.github.rohithv16.aem.analyser.core.model.report.Violation>> buckets =
                PerModuleSummary.bucket(attributor, report.violations());
        Map<String, List<io.github.rohithv16.aem.analyser.core.model.report.Violation>> accepted =
                PerModuleSummary.bucket(attributor, report.acceptedViolations());

        List<String> table = PerModuleSummary.render(PerModuleSummary.rows(buckets));
        for (String line : table) {
            getLog().info(line);
        }
        if (!attributor.isReactor()) {
            // -N is the usual way to run an aggregator, but it also hides every module from the
            // session: say so instead of silently publishing a single bucket.
            getLog().warn("AEM Code Analyser: aem-analyser.perModule=true but the Maven session holds no"
                    + " nested modules (single-module build or -N); findings are reported as one bucket."
                    + " Run the goal without -N to attribute findings per module.");
        }
        if (buckets.isEmpty() && accepted.isEmpty()) {
            return;
        }

        Path modulesDirectory = defaultOutputDirectory(root).resolve("aem-analyser").resolve("modules");
        java.util.Set<String> moduleIds = new java.util.LinkedHashSet<>(buckets.keySet());
        moduleIds.addAll(accepted.keySet());
        try {
            for (String moduleId : moduleIds) {
                List<io.github.rohithv16.aem.analyser.core.model.report.Violation> findings =
                        buckets.getOrDefault(moduleId, List.of());
                List<io.github.rohithv16.aem.analyser.core.model.report.Violation> acceptedFindings =
                        accepted.getOrDefault(moduleId, List.of());
                if (findings.isEmpty() && acceptedFindings.isEmpty()) {
                    continue;
                }
                // A module report is a slice of one run: phase timings and the cache summary belong to
                // the whole run, not to one module, so they stay empty here.
                AnalysisReport moduleReport = new AnalysisReport(root, report.scanStatistics(), findings,
                        report.durationMillis(), report.coverageReport(), report.codeMetrics(),
                        report.diagnostics(), null, acceptedFindings, Map.of(), null);
                Path moduleDirectory = modulesDirectory.resolve(directoryName(moduleId));
                Files.createDirectories(moduleDirectory);
                for (ReportFormat format : formats) {
                    Path file = moduleDirectory.resolve(fileName(format));
                    try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                        exporter(format).export(moduleReport, writer);
                    }
                }
                if (generateHtml) {
                    Path htmlFile = moduleDirectory.resolve("aem-analyser-report.html");
                    try (Writer writer = Files.newBufferedWriter(htmlFile, StandardCharsets.UTF_8)) {
                        new HtmlReportExporter(java.time.Clock.systemDefaultZone(), 5000, List.of())
                                .export(moduleReport, writer);
                    }
                }
                getLog().info("Wrote per-module " + formats + " report(s) for " + moduleId + " to "
                        + moduleDirectory);
            }
        } catch (IOException | ExportException e) {
            throw new MojoExecutionException("Failed to write per-module reports: " + e.getMessage(), e);
        }
    }

    /**
     * A module id is an artifactId, so it is already a safe directory name; the sanitiser only guards
     * against a hand-set artifactId containing a separator or {@code ..}.
     */
    private static String directoryName(String moduleId) {
        String safe = moduleId == null ? "_reactor" : moduleId.replaceAll("[^A-Za-z0-9._-]", "_");
        while (safe.startsWith(".")) {
            safe = "_" + safe.substring(1);
        }
        return safe.isEmpty() ? "_reactor" : safe;
    }

    private static String fileName(ReportFormat format) {
        return switch (format) {
            case SARIF -> "aem-analyser.sarif";
            case SONAR -> "sonar-aem-analyser-report.json";
            case CONSOLE -> "aem-analyser-report.txt";
            case HTML -> "aem-analyser-report.html";
            case GITHUB -> "github-annotations.txt";
            case GITLAB -> "gitlab-code-quality.json";
            case AZURE -> "azure-pipelines.log";
            case JSON -> "aem-analyser-report.json";
            case TEXT -> "aem-analyser.txt";
            case JUNIT -> "aem-analyser-junit.xml";
        };
    }

    private static ReportExporter exporter(ReportFormat format) {
        return switch (format) {
            case SARIF -> new SarifReportExporter();
            case SONAR -> new SonarReportExporter();
            case CONSOLE -> new ConsoleReportFormatter(false);
            case HTML -> new HtmlReportExporter();
            case GITHUB -> new io.github.rohithv16.aem.analyser.core.export.GitHubAnnotationExporter();
            case GITLAB -> new io.github.rohithv16.aem.analyser.core.export.GitLabCodeQualityExporter();
            case AZURE -> new io.github.rohithv16.aem.analyser.core.export.AzurePipelinesExporter();
            case JSON -> new io.github.rohithv16.aem.analyser.core.export.JsonReportExporter();
            case TEXT -> new io.github.rohithv16.aem.analyser.core.export.TextReportExporter();
            case JUNIT -> new io.github.rohithv16.aem.analyser.core.export.JUnitXmlExporter();
        };
    }

    private static List<String> splitCsv(String value) {
        List<String> result = new ArrayList<>();
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static Set<String> splitCsvSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(splitCsv(value));
    }
}
