package io.github.rohithv16.aem.analyser.cli;

import io.github.rohithv16.aem.analyser.core.api.AnalysisContext;
import io.github.rohithv16.aem.analyser.core.api.AnalysisException;
import io.github.rohithv16.aem.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.config.AnalyserConfig;
import io.github.rohithv16.aem.analyser.core.config.AnalyserConfigLoader;
import io.github.rohithv16.aem.analyser.core.export.ColorSupport;
import io.github.rohithv16.aem.analyser.core.export.ConsoleReportFormatter;
import io.github.rohithv16.aem.analyser.core.export.ExportException;
import io.github.rohithv16.aem.analyser.core.export.HtmlReportExporter;
import io.github.rohithv16.aem.analyser.core.export.ReportFormat;
import io.github.rohithv16.aem.analyser.core.export.SarifReportExporter;
import io.github.rohithv16.aem.analyser.core.export.SonarReportExporter;
import io.github.rohithv16.aem.analyser.core.fix.AutoFixEngine;
import io.github.rohithv16.aem.analyser.core.git.GitDiffResolver;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.GateVerdict;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleCatalog;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

/**
 * FEAT-DELIV-02: standalone Picocli command-line executable.
 * Designed for sub-second developer inner-loop scans, pre-commit hooks and
 * standalone audits of unbuilt repositories.
 */
@Command(name = "aem-analyser",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "AEM Code Analyser — cross-boundary dead code, duplication and "
                + "AEM as a Cloud Service analysis for Adobe Experience Manager projects.")
public class AnalyserCli implements java.util.concurrent.Callable<Integer> {

    @Option(names = {"-p", "--project-root"},
            description = "Project (reactor) root. Default: the current directory, or the "
                    + "reactor that owns it when run inside a module.")
    private Path projectRoot;

    @Parameters(index = "0", arity = "0..1", paramLabel = "PROJECT_ROOT",
            description = "Project root; shorthand for --project-root.")
    private Path positionalProjectRoot;

    @Option(names = {"-f", "--format"}, split = ",",
            description = "Output formats (comma-separated): CONSOLE, SARIF, SONAR, HTML, GITHUB, GITLAB, AZURE. "
                    + "Default: CONSOLE (the HTML dashboard is also generated unless --no-html).")
    private List<String> formats = List.of("CONSOLE");

    @Option(names = {"--html", "--html-report"}, arity = "0..1", fallbackValue = "",
            description = "Generate an interactive standalone HTML report dashboard (default path unless --no-html).")
    private String htmlReport;

    @Option(names = {"--no-html"},
            description = "Do not generate the HTML dashboard for this run.")
    private boolean noHtml;

    @Option(names = {"-o", "--output"},
            description = "Output directory for SARIF/SONAR/HTML files, or a file path whose extension "
                    + "selects the format written there (e.g. -o out/aem.sarif writes the SARIF report "
                    + "to that file). Default: ./target/aem-analyser")
    private Path outputDirectory;

    @Option(names = {"-t", "--threads"},
            description = "Worker thread count for parallel parsing. Default: available processors - 1.")
    private Integer threads;

    @Option(names = {"--max-file-size"},
            description = "Skip files larger than this many bytes (default: 10485760 = 10 MiB).")
    private Long maxFileSizeBytes;

    @Option(names = {"--timeout"},
            description = "Overall analysis timeout in seconds (0 or omitted = no timeout).")
    private Long timeoutSeconds;

    @Option(names = {"--profile"}, split = ",",
            description = "Maven profiles to activate during module discovery.")
    private List<String> profiles;

    @Option(names = {"-w", "--fail-on-warning"},
            description = "Exit with a non-zero code when warnings are detected.")
    private boolean failOnWarning;

    @Option(names = {"--no-color"}, description = "Disable ANSI colors in console output.")
    private boolean noColor;

    @Option(names = {"-v", "--verbose"},
            description = "Print structured analysis diagnostics (unparseable files, skipped trees) on stderr.")
    private boolean verbose;

    @Option(names = {"-q", "--quiet"},
            description = "Suppress progress and warning lines on stderr. stdout still carries the report "
                    + "on its own; errors are always reported.")
    private boolean quiet;

    @Option(names = "--severity", split = ",", paramLabel = "RULE=SEVERITY",
            description = "Per-rule severity for this run: RULE=ERROR|WARNING|INFO|OFF. Repeatable or "
                    + "comma-separated (e.g. --severity AEM-QUAL-003=INFO,AEM-DUP-010=OFF). "
                    + "Overrides the project configuration; unknown rule ids are rejected.")
    private List<String> severityOverrides;

    @Option(names = "--tag", paramLabel = "TAG",
            description = "With --list-rules: list only rules carrying TAG (e.g. osgi, security, performance).")
    private String tagFilter;

    @Option(names = {"--ignored-services"}, split = ",",
            description = "Service implementation class names to whitelist (never flagged unused).")
    private Set<String> ignoredServices;

    @Option(names = {"--ignore-config", "--ignore-config-path"}, split = ",",
            description = "Reactor-relative glob patterns of OSGi configuration files to exclude "
                    + "(e.g. 'ui.config/**/config.publish/**' or '*.cfg.json').")
    private Set<String> ignoredConfigPaths;

    @Option(names = {"--dependency-jars"}, split = ",",
            description = "JAR files or directories to index for external/platform PID suppression.")
    private List<Path> dependencyJarRoots;

    @Option(names = {"--collect-coverage"},
            description = "Automatically execute unit tests with dynamic JaCoCo instrumentation to generate fresh coverage reports (zero POM configuration required).")
    private boolean collectCoverage;

    @Option(names = {"--force"},
            description = "Force re-execution of unit tests when using --collect-coverage even if coverage artifacts already exist.")
    private boolean force;

    @Option(names = {"--min-coverage"},
            description = "Quality Gate: minimum required test line coverage percentage (e.g. --min-coverage 80.0).")
    private Double minCoverage;

    @Option(names = {"--max-duplication"},
            description = "Quality Gate: maximum tolerated code duplication percentage (e.g. --max-duplication 3.0).")
    private Double maxDuplication;

    @Option(names = {"--max-warnings"},
            description = "Quality Gate: maximum tolerated warning count before failing the build.")
    private Integer maxWarnings;

    // fallbackValue is required: without it picocli treats a default-true flag as a *toggle*, so
    // "--sonar" would set the field to false and silently drop every SonarJava finding.
    @Option(names = {"--sonar"}, negatable = true, defaultValue = "true", fallbackValue = "true",
            description = "Enable embedded SonarJava analysis. Use --no-sonar to disable it (default: enabled).")
    private boolean sonar = true;

    @Option(names = {"--sonar-rules"},
            description = "SonarJava ruleset filter (default: 'default', or comma-separated rule IDs e.g. S106,S1147).")
    private String sonarRules;

    @Option(names = {"--changed-only"},
            description = "Scan only files and lines modified in Git.")
    private boolean changedOnly;

    @Option(names = {"--since"},
            description = "Git reference or commit to compare changes against (e.g. HEAD~1, origin/main). Implies --changed-only.")
    private String since;

    @Option(names = {"--fix"},
            description = "Automatically fix safe, deterministic code smells (e.g. duplicate imports, redundant boolean literals, StandardCharsets).")
    private boolean fix;

    @Option(names = {"--fix-dry-run"},
            description = "Preview automated code smell fixes without writing changes to disk.")
    private boolean fixDryRun;

    @Option(names = {"--baseline"},
            description = "Baseline JSON file of accepted findings. Default: .aem-analyser-baseline.json when present.")
    private Path baseline;

    @Option(names = {"--update-baseline"},
            description = "Write current findings to the baseline file, then report zero remaining findings.")
    private boolean updateBaseline;

    @Option(names = {"--baseline-report"},
            description = "Keep baseline-suppressed findings in reports at INFO severity.")
    private boolean baselineReport;

    @Option(names = {"--history"}, paramLabel = "DIR",
            description = "Append a run summary to DIR/runs.jsonl and render the trend in the HTML report.")
    private Path historyDirectory;

    @Option(names = {"--history-limit"},
            description = "Number of historical runs shown in the HTML trend (default: 20).")
    private int historyLimit = 20;

    @Option(names = {"--config"}, paramLabel = "FILE",
            description = "Project configuration YAML (default: .aem-analyser.yml when present).")
    private Path configFile;

    @Option(names = {"--list-rules"},
            description = "List every rule with its effective severity and effort, then exit.")
    private boolean listRules;

    @Option(names = {"--explain"}, paramLabel = "RULE",
            description = "Print the effective metadata for one rule (e.g. AEM-DEAD-002A), then exit.")
    private String explainRule;

    @Option(names = {"--generate-rule-docs"}, paramLabel = "DIR",
            description = "Generate the HTML issue reference into DIR/issues, then exit.")
    private Path generateRuleDocs;

    @Option(names = {"--fail-on"},
            description = "Fail build if violations at or above severity exist: ERROR, WARNING, INFO.")
    private String failOn;

    boolean isSonar() {
        return sonar;
    }

    boolean isNoSonar() {
        return !sonar;
    }

    /**
     * Colour is off unless standard output is a terminal: a redirected report must stay clean, and
     * {@code --no-color} / {@code NO_COLOR} remain the explicit opt-outs.
     */
    boolean consoleColorEnabled() {
        return !noColor && ColorSupport.enabled(ColorSupport.stdoutIsTerminal());
    }

    /**
     * Report channel of the tool. A CLI writes its report to standard output by design, so this is
     * deliberately not a logging framework: {@code --no-color}, report formats and CI annotations need
     * the raw stream, and levels or timestamps would corrupt machine-readable output.
     */
    @SuppressWarnings("java:S106")
    private final PrintWriter stdout = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

    /**
     * Progress channel. Everything that is not the report — {@code [INFO]}/{@code [WARN]} lines, "report
     * written to …", diagnostics — goes here, so {@code aem-analyser … > report.txt} leaves a file that
     * parses as a report and a terminal that still shows progress. {@code -q} silences it.
     */
    @SuppressWarnings("java:S106")
    private final PrintWriter stderr = new PrintWriter(System.err, true, StandardCharsets.UTF_8);

    /** Progress line, suppressed by {@code -q}. */
    private void progress(String line) {
        if (!quiet) {
            stderr.println(line);
        }
    }

    private void progressf(String format, Object... args) {
        if (!quiet) {
            stderr.printf(format, args);
        }
    }

    /** Error line: never suppressed by {@code -q}, because a silent failure is undebuggable. */
    private void error(String message) {
        stderr.println("[ERROR] " + message);
    }

    @Override
    public Integer call() {
        return execute();
    }

    /**
     * The process exit code is the command's return value: discarding it turned a usage error into a
     * silent "gate passed" for any CI step that reads the status.
     *
     * <p>Contract: 0 pass, 1 gate failure, 2 usage/analysis error, 3 internal error. An uncaught crash
     * is not a verdict on the code — picocli's default 1 would read as a failed gate — so it gets its
     * own code instead of colliding with either.
     */
    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new AnalyserCli());
        commandLine.setExecutionExceptionHandler((exception, cmd, parseResult) -> {
            cmd.getErr().println("[ERROR] Internal error: " + exception);
            exception.printStackTrace(cmd.getErr());
            return 3;
        });
        System.exit(commandLine.execute(args));
    }

    int execute() {
        OptionalInt metaCommandResult = executeMetaCommand();
        if (metaCommandResult.isPresent()) {
            return metaCommandResult.getAsInt();
        }
        try {
            return runAnalysis();
        } catch (AnalysisException e) {
            error("Analysis failed: " + e.getMessage());
            return 2;
        } catch (IOException | ExportException e) {
            error("Report export failed: " + e.getMessage());
            return 2;
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return 2;
        }
    }

    /** Meta commands ({@code --generate-rule-docs}, {@code --list-rules}, {@code --explain}) exit early. */
    private OptionalInt executeMetaCommand() {
        if (generateRuleDocs != null) {
            return OptionalInt.of(writeRuleDocs(generateRuleDocs));
        }
        if (tagFilter != null && !listRules) {
            error("--tag can only be used with --list-rules.");
            return OptionalInt.of(2);
        }
        if (!listRules && (explainRule == null || explainRule.isBlank())) {
            return OptionalInt.empty();
        }
        // Introspection must describe the run the user is about to make: the same config file, the
        // same --severity layer. Reading only the registry defaults made the catalogue disagree with
        // the report it explains.
        EffectiveConfig effective;
        try {
            effective = loadConfig(resolveProjectRoot());
        } catch (IOException e) {
            error("Could not read configuration: " + e.getMessage());
            return OptionalInt.of(2);
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return OptionalInt.of(2);
        }
        if (listRules) {
            return OptionalInt.of(printRules(stdout, effective, tagFilter));
        }
        return OptionalInt.of(printExplain(stdout, explainRule, effective));
    }

    private int writeRuleDocs(Path directory) {
        try {
            io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDocsGenerator.writeAll(directory);
            progress("[INFO] Rule documentation written to " + directory);
            return 0;
        } catch (IOException e) {
            error("Could not write rule documentation: " + e.getMessage());
            return 2;
        }
    }

    private int runAnalysis() throws IOException, ExportException, AnalysisException {
        Path root = resolveProjectRoot();
        List<ReportFormat> reportFormats = parseFormats();
        validateFailOn();
        validateNumericOptions();
        if (collectCoverage) {
            runCoverageCollection(root);
        }
        EffectiveConfig effective;
        try {
            effective = loadConfig(root);
        } catch (IOException e) {
            error("Could not read configuration: " + e.getMessage());
            return 2;
        }
        AnalyserConfig config = effective.config();
        AnalysisContext context = scopeToChangedFiles(buildContext(root, config), root);
        AnalysisReport report = analyzeAndFilter(context, root);
        report = applyAutoFix(root, context, report);
        report = config.apply(report);
        report = applyBaseline(root, report);
        List<io.github.rohithv16.aem.analyser.core.history.RunSummary> history = applyHistory(report);
        // The gate is evaluated once, before anything renders: the console block, the dashboard and
        // the exit code all have to describe the same run.
        printQualityGate(report);
        GateVerdict verdict = evaluateGate(report);
        export(report.withGateVerdict(verdict), reportFormats, history);
        printDiagnostics(report);
        return verdict.exitCode();
    }

    /**
     * The effective configuration for one invocation: the file layer with the {@code --severity} layer
     * merged on top, plus where each value came from so {@code --list-rules}/{@code --explain} can name
     * the provenance instead of guessing.
     *
     * @param config      effective rules and allowlists (CLI wins over the file)
     * @param source      file name of the config that applied, or {@code null} when none was found
     * @param cliRuleIds  canonical ids named by {@code --severity}, i.e. the values the CLI owns
     */
    record EffectiveConfig(AnalyserConfig config, String source, Set<String> cliRuleIds) {

        static EffectiveConfig of(AnalyserConfig config, String source, Set<String> cliRuleIds) {
            return new EffectiveConfig(config, source, Set.copyOf(cliRuleIds));
        }

        /** Where the effective value for {@code ruleId} came from, or {@code null} for the default. */
        String provenance(String ruleId) {
            if (cliRuleIds.contains(ruleId)) {
                return "--severity";
            }
            return source;
        }
    }

    private EffectiveConfig loadConfig(Path root) throws IOException {
        AnalyserConfig fileConfig;
        String source;
        if (configFile != null) {
            fileConfig = AnalyserConfigLoader.load(configFile);
            source = configFile.getFileName() != null
                    ? configFile.getFileName().toString() : configFile.toString();
        } else {
            Path defaultFile = root.resolve(AnalyserConfigLoader.DEFAULT_FILE);
            fileConfig = AnalyserConfigLoader.loadIfPresent(root);
            source = Files.isRegularFile(defaultFile) ? AnalyserConfigLoader.DEFAULT_FILE : null;
        }
        Map<String, AnalyserConfig.RuleOverride> merged = new LinkedHashMap<>(fileConfig.rules());
        Set<String> cliRuleIds = new TreeSet<>();
        applySeverityOverrides(merged, cliRuleIds);
        AnalyserConfig config = new AnalyserConfig(fileConfig.schemaVersion(), merged, fileConfig.allowlists());
        warnRetiredRuleIds(config.rules().keySet(), source == null ? "Configuration" : "Configuration " + source);
        return EffectiveConfig.of(config, source, cliRuleIds);
    }

    /**
     * Merges {@code --severity RULE=SEVERITY} over the file rules. A CLI entry is explicit, so it wins
     * for both axes: {@code OFF} disables the rule, a severity enables it at that severity even when
     * the file disabled it. Unknown ids are rejected rather than silently accepted — a typo would
     * otherwise look like a rule policy that never applied.
     */
    private void applySeverityOverrides(Map<String, AnalyserConfig.RuleOverride> merged,
                                        Set<String> cliRuleIds) {
        if (severityOverrides == null) {
            return;
        }
        for (String spec : severityOverrides) {
            if (spec == null || spec.isBlank()) {
                continue;
            }
            int equals = spec.indexOf('=');
            if (equals <= 0 || equals == spec.length() - 1) {
                throw new IllegalArgumentException("Invalid --severity '" + spec.strip()
                        + "'. Expected RULE=SEVERITY, e.g. --severity AEM-QUAL-003=INFO.");
            }
            String rawId = spec.substring(0, equals).strip();
            String rawSeverity = spec.substring(equals + 1).strip().toUpperCase(Locale.ROOT);
            String ruleId = io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry.find(rawId)
                    .map(io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDescriptor::id)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown rule '" + rawId
                            + "' in --severity. Run --list-rules to see the rule ids."));
            if ("OFF".equals(rawSeverity) || "NONE".equals(rawSeverity)) {
                merged.put(ruleId, new AnalyserConfig.RuleOverride(false, null));
            } else {
                Severity severity;
                try {
                    severity = Severity.valueOf(rawSeverity);
                } catch (IllegalArgumentException unknown) {
                    throw new IllegalArgumentException("Unknown severity '" + spec.substring(equals + 1).strip()
                            + "' for rule " + ruleId + " in --severity."
                            + " Supported values: ERROR, WARNING, INFO, OFF.");
                }
                merged.put(ruleId, new AnalyserConfig.RuleOverride(true, severity));
            }
            cliRuleIds.add(ruleId);
        }
    }

    /**
     * Warns when a configuration or baseline names a rule that no longer runs. Silently accepting the
     * id lets a team believe a check is enabled or disabled when nothing emits it.
     */
    private void warnRetiredRuleIds(Set<String> ruleIds, String source) {
        Set<String> warned = new TreeSet<>();
        for (String ruleId : ruleIds) {
            if (RuleCatalog.isRetired(ruleId) && warned.add(ruleId.strip().toUpperCase(Locale.ROOT))) {
                progress("[WARNING] " + source + " references retired rule '" + ruleId + "' — "
                        + RuleCatalog.retirementNote(ruleId)
                        + "; it produces no findings, so the entry has no effect.");
            }
        }
        if (!warned.isEmpty()) {
            progress("[WARNING] Run --list-rules to see the active rule set.");
        }
    }

    /** Applies command-line overrides and configured allowlists, then builds the analysis context. */
    private AnalysisContext buildContext(Path root, AnalyserConfig config) {
        AnalysisContext.Builder builder = AnalysisContext.builder(root);
        Set<String> effectiveIgnoredServices = new java.util.LinkedHashSet<>(
                ignoredServices == null ? Set.of() : ignoredServices);
        effectiveIgnoredServices.addAll(config.allowlists().services());
        Set<String> effectiveIgnoredConfigs = new java.util.LinkedHashSet<>(
                ignoredConfigPaths == null ? Set.of() : ignoredConfigPaths);
        effectiveIgnoredConfigs.addAll(config.allowlists().configPaths());
        if (!effectiveIgnoredServices.isEmpty()) {
            builder = builder.ignoredServices(effectiveIgnoredServices);
        }
        if (!effectiveIgnoredConfigs.isEmpty()) {
            builder = builder.ignoredConfigPaths(effectiveIgnoredConfigs);
        }
        if (profiles != null && !profiles.isEmpty()) {
            builder = builder.activeProfiles(profiles);
        }
        if (threads != null && threads > 0) {
            builder = builder.threads(threads);
        }
        if (maxFileSizeBytes != null) {
            builder = builder.maxFileSizeBytes(maxFileSizeBytes);
        }
        if (timeoutSeconds != null) {
            builder = builder.timeoutMillis(timeoutSeconds * 1000L);
        }
        if (dependencyJarRoots != null && !dependencyJarRoots.isEmpty()) {
            builder = builder.dependencyJarRoots(dependencyJarRoots);
        }
        builder = builder.enableSonar(sonar);
        if (sonarRules != null && !sonarRules.isBlank()) {
            builder = builder.sonarRules(sonarRules);
        }
        return builder.build();
    }

    /** Narrows the context to changed files when {@code --changed-only} or {@code --since} is set. */
    private AnalysisContext scopeToChangedFiles(AnalysisContext context, Path root) {
        if (!changedOnly && (since == null || since.isBlank())) {
            return context;
        }
        var diff = new GitDiffResolver().resolveChanged(root, since);
        if (diff.failed()) {
            // Never shrink the parsed file set from a failed diff: a typo in --since (or an
            // unfetched CI base ref) would otherwise report a clean build.
            progress("[WARN] " + diff.failureReason());
            progress("[WARN] Falling back to a FULL scan so findings cannot be dropped.");
            return context;
        }
        if (diff.isGitRepo()) {
            return context.toBuilder().changedFiles(diff.changedFiles()).build();
        }
        return context;
    }

    /**
     * Runs the auto-fix engine when requested. A real fix changed the sources, so the report is
     * recomputed: the pre-fix report would otherwise drive stale console output, exports and exit code.
     */
    private AnalysisReport applyAutoFix(Path root, AnalysisContext context, AnalysisReport report)
            throws AnalysisException {
        if (!fix && !fixDryRun) {
            return report;
        }
        AutoFixEngine fixEngine = new AutoFixEngine();
        var fixSummary = fixEngine.fix(root, report.violations(), fixDryRun);
        if (fixSummary.hasFixes()) {
            progressf("[INFO] %s %d issue(s) across %d file(s):%n",
                    fixDryRun ? "Auto-fix dry-run: would repair" : "Auto-fix successfully repaired",
                    fixSummary.fixesApplied(), fixSummary.filesModified());
            for (var repair : fixSummary.records()) {
                progress("  - [" + repair.ruleId() + "] " + repair.filePath() + ": "
                        + repair.description());
            }
            if (!fixDryRun) {
                progress("[INFO] Re-analysing after auto-fix...");
                report = analyzeAndFilter(context, root);
            }
        } else {
            progress("[INFO] No auto-fixable code smells detected in target files.");
        }
        for (String failure : fixSummary.writeFailures()) {
            progress("[WARNING] Could not auto-fix " + failure);
        }
        return report;
    }

    private AnalysisReport applyBaseline(Path root, AnalysisReport report) throws IOException {
        Path path = baseline;
        Path defaultPath = root.resolve(".aem-analyser-baseline.json");
        if (updateBaseline) {
            if (path == null) {
                path = defaultPath;
            }
            io.github.rohithv16.aem.analyser.core.baseline.BaselineStore.save(path, report);
            progress("[INFO] Baseline written to " + path + " ("
                    + report.violations().size() + " finding(s) accepted).");
        } else if (path == null && Files.isRegularFile(defaultPath)) {
            path = defaultPath;
        }
        if (path == null) {
            return report;
        }
        if (!Files.isRegularFile(path)) {
            if (!updateBaseline) {
                progress("[WARNING] Baseline file not found: " + path);
            }
            return report;
        }
        io.github.rohithv16.aem.analyser.core.baseline.Baseline loaded =
                io.github.rohithv16.aem.analyser.core.baseline.BaselineStore.load(path);
        warnRetiredRuleIds(loaded.entries().stream()
                .map(io.github.rohithv16.aem.analyser.core.baseline.BaselineEntry::ruleId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)),
                "Baseline " + path.getFileName());
        int suppressed = (int) report.violations().stream()
                .filter(v -> loaded.contains(io.github.rohithv16.aem.analyser.core.baseline.Fingerprint.of(v)))
                .count();
        AnalysisReport filtered = io.github.rohithv16.aem.analyser.core.baseline.BaselineFilter
                .filter(report, loaded, baselineReport);
        progress("[INFO] Baseline suppressed " + suppressed + " accepted finding(s).");
        return filtered;
    }

    private void printDiagnostics(AnalysisReport report) {
        if (!verbose || report.diagnostics().isEmpty()) {
            return;
        }
        progress("[INFO] Analysis diagnostics (" + report.diagnostics().size() + "):");
        for (var diagnostic : report.diagnostics()) {
            String location = diagnostic.line() > 0
                    ? diagnostic.path() + ":" + diagnostic.line()
                    : diagnostic.path();
            progress("  - [" + diagnostic.kind() + "] " + location + ": " + diagnostic.reason());
        }
    }

    void printRules(PrintWriter out) {
        printRules(out, EffectiveConfig.of(AnalyserConfig.empty(), null, Set.of()), null);
    }

    /**
     * Lists the <em>effective</em> rule set: the registry, adjusted by the project config and
     * {@code --severity}. A catalogue that shows defaults while the run honours a config is worse than
     * no catalogue — it invites a team to disable a rule that still runs.
     */
    int printRules(PrintWriter out, EffectiveConfig effective, String tag) {
        List<io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDescriptor> rules =
                io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry.all();
        if (tag != null && !tag.isBlank()) {
            String wanted = tag.strip();
            rules = rules.stream()
                    .filter(rule -> rule.tags().stream().anyMatch(t -> t.equalsIgnoreCase(wanted)))
                    .toList();
            if (rules.isEmpty()) {
                error("No rules tagged '" + wanted + "'. Known tags: " + knownTags() + ".");
                return 2;
            }
        }
        String header = "AEM Code Analyser rules" + (tag == null || tag.isBlank() ? "" : " tagged '" + tag.strip() + "'");
        if (effective.source() != null) {
            header += " (config: " + effective.source() + ")";
        }
        out.println(header + ":");
        for (var rule : rules) {
            out.printf("  %-16s %-8s %3dm  %s",
                    rule.id(), severityCell(rule, effective), rule.effortMinutes(),
                    rule.shortDescription());
            String note = provenanceNote(rule, effective);
            if (!note.isEmpty()) {
                out.print(" [" + note + "]");
            }
            out.println();
        }
        out.flush();
        return 0;
    }

    /** The severity column of one {@code --list-rules} line, reflecting disablement and overrides. */
    private static String severityCell(
            io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDescriptor rule,
            EffectiveConfig effective) {
        if (RuleCatalog.isRetired(rule.id())) {
            return "RETIRED";
        }
        AnalyserConfig.RuleOverride override = effective.config().rules().get(rule.id());
        if (override != null && override.disabled()) {
            return "DISABLED";
        }
        if (override != null && override.severity() != null
                && override.severity() != rule.defaultSeverity()) {
            return override.severity().name() + "*";
        }
        return rule.defaultSeverity().name();
    }

    /** Why a rule reads differently from its default: disabled, overridden, or retired. */
    private static String provenanceNote(
            io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDescriptor rule,
            EffectiveConfig effective) {
        if (RuleCatalog.isRetired(rule.id())) {
            return RuleCatalog.retirementNote(rule.id());
        }
        AnalyserConfig.RuleOverride override = effective.config().rules().get(rule.id());
        String provenance = effective.provenance(rule.id());
        if (override != null && override.disabled()) {
            return "disabled by " + (provenance == null ? "config" : provenance);
        }
        if (override != null && override.severity() != null
                && override.severity() != rule.defaultSeverity()) {
            return (provenance == null ? "config" : provenance) + "; default " + rule.defaultSeverity();
        }
        return "";
    }

    private static String knownTags() {
        Set<String> tags = new TreeSet<>();
        for (var rule : io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry.all()) {
            tags.addAll(rule.tags());
        }
        return String.join(", ", tags);
    }

    int printExplain(PrintWriter out, String ruleId) {
        return printExplain(out, ruleId, EffectiveConfig.of(AnalyserConfig.empty(), null, Set.of()));
    }

    int printExplain(PrintWriter out, String ruleId, EffectiveConfig effective) {
        var descriptor = io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry.find(ruleId);
        if (descriptor.isEmpty()) {
            out.println("[ERROR] Unknown rule '" + ruleId + "'. Use --list-rules to see all rules.");
            out.flush();
            return 2;
        }
        var rule = descriptor.get();
        boolean retired = RuleCatalog.isRetired(rule.id());
        AnalyserConfig.RuleOverride override = effective.config().rules().get(rule.id());
        String provenance = effective.provenance(rule.id());
        out.println(rule.id() + " — " + rule.name());
        if (retired) {
            out.println("  Severity:     RETIRED (was " + rule.defaultSeverity() + ")");
            out.println("  State:        " + RuleCatalog.retirementNote(rule.id()));
            out.println("                No rule evaluator produces this id; a configuration or baseline "
                    + "entry that names it has no effect.");
        } else if (override != null && override.disabled()) {
            out.println("  Severity:     DISABLED (default " + rule.defaultSeverity() + ")");
            out.println("  State:        Disabled by " + (provenance == null ? "config" : provenance)
                    + " — this rule reports no findings in this run.");
        } else {
            Severity severity = override != null && override.severity() != null
                    ? override.severity() : rule.defaultSeverity();
            out.println("  Severity:     " + severity
                    + (severity == rule.defaultSeverity() ? ""
                            : " (from " + (provenance == null ? "config" : provenance)
                                    + "; default " + rule.defaultSeverity() + ")"));
        }
        out.println("  Type:         " + rule.type());
        out.println("  Effort:       " + rule.effortMinutes() + " minutes");
        out.println("  Tags:         " + String.join(", ", rule.tags()));
        if (rule.cwe() != null) {
            out.println("  CWE:          " + rule.cwe());
        }
        out.println("  Description:  " + rule.fullDescription());
        if (!rule.remediation().isBlank()) {
            out.println("  Remediation:  " + rule.remediation());
        }
        out.println("  Docs:         " + rule.helpUri());
        out.flush();
        return 0;
    }

    List<ReportFormat> parseFormats() {
        List<ReportFormat> parsed = new ArrayList<>();
        for (String format : formats) {
            String candidate = format == null ? "" : format.strip().toUpperCase(Locale.ROOT);
            try {
                ReportFormat parsedFormat = ReportFormat.valueOf(candidate);
                // Duplicate entries (e.g. -f CONSOLE,console) must not double-write or double-print.
                if (!parsed.contains(parsedFormat)) {
                    parsed.add(parsedFormat);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown report format '" + format
                        + "'. Supported formats: CONSOLE, SARIF, SONAR, HTML, GITHUB, GITLAB, AZURE.");
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("No report format configured. "
                    + "Supported formats: CONSOLE, SARIF, SONAR, HTML, GITHUB, GITLAB, AZURE.");
        }
        // --no-html is an explicit escalation: it wins even when HTML was requested via -f HTML.
        if (noHtml) {
            parsed.remove(ReportFormat.HTML);
        }
        return parsed;
    }

    void validateNumericOptions() {
        if (threads != null && threads < 1) {
            throw new IllegalArgumentException("--threads must be at least 1 (or omit it for the default).");
        }
        if (minCoverage != null && (minCoverage < 0 || minCoverage > 100)) {
            throw new IllegalArgumentException("--min-coverage must be between 0 and 100.");
        }
        if (maxDuplication != null && (maxDuplication < 0 || maxDuplication > 100)) {
            throw new IllegalArgumentException("--max-duplication must be between 0 and 100.");
        }
        if (maxWarnings != null && maxWarnings < 0) {
            throw new IllegalArgumentException("--max-warnings must be 0 or greater.");
        }
        if (maxFileSizeBytes != null && maxFileSizeBytes <= 0) {
            throw new IllegalArgumentException("--max-file-size must be greater than 0.");
        }
        if (timeoutSeconds != null && timeoutSeconds < 0) {
            throw new IllegalArgumentException("--timeout must be 0 or greater.");
        }
    }

    private void validateFailOn() {
        if (failOn == null || failOn.isBlank()) {
            return;
        }
        String candidate = failOn.trim().toUpperCase(Locale.ROOT);
        if (!candidate.equals("ERROR") && !candidate.equals("WARNING") && !candidate.equals("INFO")
                && !candidate.equals("NONE")) {
            throw new IllegalArgumentException("Unknown --fail-on severity '" + failOn
                    + "'. Supported values: NONE, ERROR, WARNING, INFO.");
        }
    }

    private AnalysisReport analyzeAndFilter(AnalysisContext context, Path root) throws AnalysisException {
        AnalysisReport report = new DefaultAemCodeAnalyser().analyze(context);
        if (changedOnly || (since != null && !since.isBlank())) {
            GitDiffResolver gitDiffResolver = new GitDiffResolver();
            var diffResult = gitDiffResolver.resolveChanged(root, since);
            if (diffResult.failed()) {
                progress("[WARN] " + diffResult.failureReason());
                progress("[WARN] Git-diff filtering disabled for this run: reporting every finding"
                        + " from a full scan.");
            } else if (diffResult.isGitRepo()) {
                if (diffResult.changedFiles().isEmpty()) {
                    progress("[WARN] Git-diff filtering is active but NO changed files were detected."
                            + " Every finding is filtered out and this run cannot fail on content."
                            + " Drop --changed-only/--since for a full scan.");
                }
                progressf("[INFO] Git-diff filtering active (%d changed file(s) found);"
                        + " scoping violations to changed files and lines.%n",
                        diffResult.changedFiles().size());
                List<Violation> filtered = report.violations().stream()
                        .filter(v -> diffResult.matches(v.filePath(), v.region() != null ? v.region().startLine() : 0))
                        .toList();
                report = report.withViolations(filtered);
            } else {
                progress("[INFO] Directory is not a git repository or git command failed; proceeding with full scan.");
            }
        }
        return report;
    }

    /**
     * Auto-discovers the reactor root when invoked from inside a project.
     *
     * <p>Starting from {@code --project-root} (or the positional argument, or the working directory)
     * this walks up past module POMs: a POM that declares a parent which exists on disk is a module,
     * and analysing just that module would miss every cross-module finding. It stops at the topmost
     * local POM, so running inside {@code bundle/} analyses the whole reactor.
     */
    Path resolveProjectRoot() {
        Path explicit = projectRoot != null ? projectRoot : positionalProjectRoot;
        Path start = explicit != null ? explicit.toAbsolutePath().normalize()
                : Path.of("").toAbsolutePath().normalize();
        Path candidate = Files.isRegularFile(start.resolve(POM_FILE)) ? start : enclosingPomRoot(start);
        for (int depth = 0; depth < MAX_REACTOR_DEPTH && isLocalModulePom(candidate); depth++) {
            candidate = candidate.getParent();
        }
        return candidate;
    }

    /** The Maven POM file name. */
    static final String POM_FILE = "pom.xml";

    /** Rule-of-80 separator used around the quality-gate block. */
    private static final String SEPARATOR = "-".repeat(80);

    private static final int MAX_REACTOR_DEPTH = 8;

    /** The nearest ancestor (or the start itself) that contains a POM. */
    private static Path enclosingPomRoot(Path from) {
        Path current = from;
        for (int i = 0; i < 6 && current.getParent() != null; i++) {
            current = current.getParent();
            if (Files.isRegularFile(current.resolve(POM_FILE))) {
                return current;
            }
        }
        return from;
    }

    /**
     * True when {@code dir/pom.xml} is a module of a parent POM that is checked out next to it, i.e.
     * the directory is part of a local reactor rather than a standalone project.
     */
    static boolean isLocalModulePom(Path dir) {
        if (dir == null) {
            return false;
        }
        Path pom = dir.resolve(POM_FILE);
        if (!Files.isRegularFile(pom)) {
            return false;
        }
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            org.w3c.dom.Document document = factory.newDocumentBuilder().parse(pom.toFile());
            org.w3c.dom.NodeList parents = document.getElementsByTagName("parent");
            if (parents.getLength() == 0) {
                return false;
            }
            org.w3c.dom.Element parent = (org.w3c.dom.Element) parents.item(0);
            // Maven's default for a missing <relativePath> is ../pom.xml; an empty one disables the
            // local lookup (parent comes from a repository), so the directory is not part of this
            // reactor.
            String relativePath = "../pom.xml";
            org.w3c.dom.NodeList explicit = parent.getElementsByTagName("relativePath");
            if (explicit.getLength() > 0) {
                relativePath = explicit.item(0).getTextContent().trim();
            }
            if (relativePath.isEmpty()) {
                return false;
            }
            return Files.isRegularFile(dir.resolve(relativePath).normalize());
        } catch (Exception unreadable) {
            // A POM we cannot parse is treated as standalone: never guess a wider root.
            return false;
        }
    }


    private List<io.github.rohithv16.aem.analyser.core.history.RunSummary> applyHistory(AnalysisReport report)
            throws IOException {
        if (historyDirectory == null) {
            return List.of();
        }
        io.github.rohithv16.aem.analyser.core.history.HistoryStore.append(historyDirectory, report);
        return io.github.rohithv16.aem.analyser.core.history.HistoryStore
                .load(historyDirectory, historyLimit);
    }

    private void export(AnalysisReport report, List<ReportFormat> reportFormats,
                        List<io.github.rohithv16.aem.analyser.core.history.RunSummary> history)
            throws IOException, ExportException {
        for (ReportFormat reportFormat : reportFormats) {
            switch (reportFormat) {
                case CONSOLE -> new ConsoleReportFormatter(consoleColorEnabled()).export(report, stdout);
                case SARIF -> {
                    Path target = outputTarget("aem-analyser.sarif");
                    try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                        new SarifReportExporter().export(report, writer);
                    }
                    progress("SARIF report written to " + target);
                }
                case SONAR -> {
                    Path target = outputTarget("sonar-aem-analyser-report.json");
                    try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                        new SonarReportExporter().export(report, writer);
                    }
                    progress("SonarQube report written to " + target);
                }
                case HTML -> writeHtmlReport(report, history, htmlTarget());
                case GITHUB -> {
                    Path target = outputTarget("github-annotations.txt");
                    try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                        new io.github.rohithv16.aem.analyser.core.export.GitHubAnnotationExporter()
                                .export(report, writer);
                    }
                    progress("GitHub annotations written to " + target);
                }
                case GITLAB -> {
                    Path target = outputTarget("gitlab-code-quality.json");
                    try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                        new io.github.rohithv16.aem.analyser.core.export.GitLabCodeQualityExporter()
                                .export(report, writer);
                    }
                    progress("GitLab Code Quality report written to " + target);
                }
                case AZURE -> {
                    Path target = outputTarget("azure-pipelines.log");
                    try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                        new io.github.rohithv16.aem.analyser.core.export.AzurePipelinesExporter()
                                .export(report, writer);
                    }
                    progress("Azure Pipelines annotations written to " + target);
                }
            }
        }
        if (!noHtml && !reportFormats.contains(ReportFormat.HTML)) {
            // --html <path> wins over the default location; otherwise the dashboard is generated on
            // every run unless explicitly disabled.
            writeHtmlReport(report, history, htmlTarget());
        }
    }

    /** Destination for the HTML dashboard: an explicit --html path, else the output directory. */
    private Path htmlTarget() throws IOException {
        if (htmlReport != null && !htmlReport.isBlank()) {
            return Path.of(htmlReport).toAbsolutePath().normalize();
        }
        return outputTarget("aem-analyser-report.html");
    }

    private void writeHtmlReport(AnalysisReport report,
                                 List<io.github.rohithv16.aem.analyser.core.history.RunSummary> history,
                                 Path target) throws IOException,
            io.github.rohithv16.aem.analyser.core.export.ExportException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            new HtmlReportExporter(java.time.Clock.systemDefaultZone(), 5000, history)
                    .export(report, writer);
        }
        progress("HTML report written to " + target);
    }

    /**
     * Resolves where one report file is written.
     *
     * <p>{@code -o} accepts either a directory or a file path. A path whose extension names a report
     * format is written as that file; anything else is a directory, so {@code -o out} keeps working.
     * When {@code -o} names a file for a <em>different</em> format (e.g. {@code -f SARIF,HTML}), the
     * other reports go to the parent directory instead of fighting over one path.
     */
    private Path outputTarget(String fileName) throws IOException {
        Path base = (outputDirectory != null ? outputDirectory : Path.of("target/aem-analyser"))
                .toAbsolutePath().normalize();
        if (!looksLikeReportFile(base)) {
            return Files.createDirectories(base).resolve(fileName);
        }
        Path parent = base.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (extensionOf(base).equals(extensionOf(Path.of(fileName)))) {
            return base;
        }
        return (parent != null ? parent : base).resolve(fileName);
    }

    /** Extensions a {@code -o} path may carry to be read as a file rather than a directory. */
    private static final Set<String> REPORT_FILE_EXTENSIONS =
            Set.of("sarif", "json", "html", "htm", "txt", "log");

    /** True when an output path names a file: an existing file, or a known report extension. */
    static boolean looksLikeReportFile(Path path) {
        if (Files.isDirectory(path)) {
            return false;
        }
        if (Files.isRegularFile(path)) {
            return true;
        }
        return REPORT_FILE_EXTENSIONS.contains(extensionOf(path));
    }

    private static String extensionOf(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            return "";
        }
        String value = name.toString();
        int dot = value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void runCoverageCollection(Path root) {
        List<String> testModules = findJavaTestModules(root);
        CoverageStatus status = checkCoverageStatus(root, testModules);

        if (!force && status.isFresh()) {
            String rel = root.relativize(status.coverageFile()).toString();
            progress("[INFO] Existing JaCoCo coverage is up to date (" + rel + "); skipping test execution (use --force to re-run).");
            return;
        }

        if (force) {
            progress("[INFO] --force specified: executing unit tests to collect fresh test code coverage...");
        } else if (status.coverageFile() != null) {
            progress("[INFO] " + status.reason() + "; re-executing unit tests for fresh coverage...");
        } else {
            progress("[INFO] Dynamic JaCoCo enabled: executing unit tests to collect test code coverage...");
        }

        List<String> cmd = resolveMavenCommand(root, testModules);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(root.toFile());
            String javaHome = System.getProperty("java.home");
            if (javaHome != null && !javaHome.isBlank()) {
                pb.environment().put("JAVA_HOME", javaHome);
                String currentPath = pb.environment().getOrDefault("PATH", System.getenv("PATH"));
                pb.environment().put("PATH", javaHome + "/bin" + (currentPath != null ? ":" + currentPath : ""));
            }
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                progress("[WARNING] Maven test execution returned code " + exitCode + "; continuing analysis with available artifacts.");
            } else {
                progress("[INFO] Test coverage collected successfully.");
            }
        } catch (IOException e) {
            progress("[WARNING] Unable to run Maven to collect coverage: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            progress("[WARNING] Coverage collection interrupted.");
        }
    }

    record CoverageStatus(Path coverageFile, boolean isFresh, String reason) {}

    CoverageStatus checkCoverageStatus(Path root, List<String> testModules) {
        if (testModules.isEmpty()) {
            return checkSingleModuleCoverage(root);
        }
        Path sampleCoverage = null;
        for (String modRel : testModules) {
            Path modDir = root.resolve(modRel);
            Path cov = findExistingCoverage(modDir);
            if (cov == null) {
                return new CoverageStatus(null, false, "Missing coverage in module " + modRel);
            }
            sampleCoverage = cov;
            CoverageStatus stale = staleModuleCoverage(root, modRel, modDir, cov);
            if (stale != null) {
                return stale;
            }
        }
        return new CoverageStatus(sampleCoverage, true, "All module coverage artifacts are up to date");
    }

    /** Coverage freshness for a project without test-bearing modules. */
    private CoverageStatus checkSingleModuleCoverage(Path root) {
        Path existing = findExistingCoverage(root);
        if (existing == null) {
            return new CoverageStatus(null, false, "No JaCoCo coverage file found");
        }
        try {
            FileTime covTime = Files.getLastModifiedTime(existing);
            Path newer = findNewerSourceFileInDir(root.resolve("src"), covTime);
            if (newer != null) {
                return new CoverageStatus(existing, false, "Source modified ("
                        + root.relativize(newer) + ")");
            }
            return new CoverageStatus(existing, true, "Coverage is up to date");
        } catch (IOException e) {
            return new CoverageStatus(existing, false, "Timestamp error");
        }
    }

    /** @return why a module's coverage is stale, or {@code null} when it is still up to date. */
    private CoverageStatus staleModuleCoverage(Path root, String modRel, Path modDir, Path cov) {
        try {
            FileTime covTime = Files.getLastModifiedTime(cov);
            Path modPom = modDir.resolve(POM_FILE);
            if (Files.isRegularFile(modPom)
                    && Files.getLastModifiedTime(modPom).compareTo(covTime) > 0) {
                return new CoverageStatus(cov, false, "Module POM modified ("
                        + root.relativize(modPom) + ")");
            }
            Path rootPom = root.resolve(POM_FILE);
            if (Files.isRegularFile(rootPom)
                    && Files.getLastModifiedTime(rootPom).compareTo(covTime) > 0) {
                return new CoverageStatus(cov, false, "Root pom.xml modified");
            }
            Path newer = findNewerSourceFileInDir(modDir.resolve("src"), covTime);
            if (newer != null) {
                return new CoverageStatus(cov, false, "Source modified ("
                        + root.relativize(newer) + ")");
            }
            return null;
        } catch (IOException e) {
            return new CoverageStatus(cov, false, "Timestamp error in module " + modRel);
        }
    }

    Path findNewerSourceFileInDir(Path dir, FileTime covTime) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".json");
                    })
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).compareTo(covTime) > 0;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private List<String> resolveMavenCommand(Path root, List<String> testModules) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path mvnw = root.resolve(isWindows ? "mvnw.cmd" : "mvnw");
        String cmd;
        if (Files.isExecutable(mvnw)) {
            cmd = mvnw.toAbsolutePath().toString();
        } else {
            cmd = isWindows ? "mvn.cmd" : "mvn";
        }
        List<String> command = new ArrayList<>();
        command.add(cmd);
        Path pomPath = root.resolve(POM_FILE);
        if (Files.isRegularFile(pomPath)) {
            command.add("-f");
            command.add(pomPath.toAbsolutePath().toString());
        }

        if (force) {
            command.add("clean");
        }
        boolean hasJacoco = projectHasJacoco(root);
        if (hasJacoco) {
            command.add("test");
        } else {
            command.add("org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent");
            command.add("test");
            command.add("org.jacoco:jacoco-maven-plugin:0.8.12:report");
        }

        // Only build and test the Java modules that actually contain unit tests
        if (testModules != null && !testModules.isEmpty()) {
            command.add("-pl");
            command.add(String.join(",", testModules));
        }

        command.add("-Dmaven.test.failure.ignore=true");
        command.add("-Dnpm.skip=true");
        command.add("-Dfrontend.skip=true");
        command.add("-Dskip.npm=true");
        command.add("-Dcheckstyle.skip=true");
        command.add("-Dspotless.check.skip=true");

        if (profiles != null && !profiles.isEmpty()) {
            command.add("-P" + String.join(",", profiles));
        }
        return command;
    }

    Path findExistingCoverage(Path root) {
        try (var stream = Files.walk(root, 4)) {
            return stream.filter(p -> {
                String s = p.toString().replace('\\', '/');
                return (s.endsWith("target/jacoco.exec") || s.endsWith("target/site/jacoco/jacoco.csv"))
                        && Files.isRegularFile(p);
            }).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    boolean projectHasJacoco(Path root) {
        try (var stream = Files.walk(root, 4)) {
            return stream.filter(p -> p.getFileName().toString().equals(POM_FILE))
                    .anyMatch(pom -> {
                        try {
                            String content = Files.readString(pom);
                            return content.contains("jacoco-maven-plugin");
                        } catch (IOException e) {
                            return false;
                        }
                    });
        } catch (IOException e) {
            return false;
        }
    }

    List<String> findJavaTestModules(Path root) {
        List<String> modules = new ArrayList<>();
        try (var stream = Files.walk(root, 3)) {
            stream.filter(p -> p.getFileName().toString().equals(POM_FILE) && !p.equals(root.resolve(POM_FILE)))
                    .forEach(pom -> {
                        Path moduleDir = pom.getParent();
                        if (Files.isDirectory(moduleDir.resolve("src/test/java"))) {
                            try {
                                String rel = root.relativize(moduleDir).toString();
                                if (!rel.isEmpty() && !modules.contains(rel)) {
                                    modules.add(rel);
                                }
                            } catch (IllegalArgumentException notRelativizable) {
                                // A module outside the analysed root cannot be named as a -pl argument,
                                // so it is skipped: coverage collection stays best-effort.
                            }
                        }
                    });
        } catch (IOException unreadableTree) {
            // An unreadable tree yields no test modules; coverage collection is best-effort and the
            // analysis itself must still run against whatever was parsed.
        }
        return modules;
    }

    /** The exit code the effective gate implies for this report. */
    int exitCode(AnalysisReport report) {
        return evaluateGate(report).exitCode();
    }

    /**
     * Computes the single verdict for this run from the <em>effective</em> gate: the implicit error
     * gate (unless report-only), {@code --fail-on}, {@code --fail-on-warning} and the coverage /
     * duplication / warning thresholds.
     *
     * <p>Every renderer prints this verdict and the process exit code is derived from it, so the
     * console block, the dashboard and the build result cannot disagree.
     */
    GateVerdict evaluateGate(AnalysisReport report) {
        long errors = report.countBySeverity(Severity.ERROR);
        long warnings = report.countBySeverity(Severity.WARNING);
        long infos = report.countBySeverity(Severity.INFO);
        boolean reportOnly = failOn != null && "NONE".equalsIgnoreCase(failOn.trim());

        List<String> failures = new ArrayList<>();
        addThresholdFailures(report, warnings, failures);
        if (!reportOnly) {
            if (errors > 0) {
                failures.add(errors + " error-severity finding" + (errors == 1 ? "" : "s") + " detected.");
            } else if (failOnExceeded(report, warnings)) {
                failures.add("findings at or above --fail-on "
                        + failOn.trim().toUpperCase(Locale.ROOT) + " detected.");
            } else if (failOnWarning && warnings > 0) {
                failures.add(warnings + " warning(s) detected and --fail-on-warning is set.");
            }
        }
        if (!failures.isEmpty()) {
            return GateVerdict.failed(String.join(" ", failures));
        }
        if (report.violations().isEmpty()) {
            return GateVerdict.passed();
        }
        if (reportOnly) {
            return GateVerdict.notEnforced(reportOnlyReason(errors, warnings, infos));
        }
        return GateVerdict.passedWithWarnings(
                warnings + " warning(s), " + infos + " info message(s).");
    }

    /** Threshold breaches, all of which gate even in {@code --fail-on NONE} report-only mode. */
    private void addThresholdFailures(AnalysisReport report, long warnings, List<String> failures) {
        if (maxWarnings != null && warnings > maxWarnings) {
            failures.add(warnings + " warning(s) exceed --max-warnings " + maxWarnings + ".");
        }
        if (maxDuplication != null) {
            double duplication = report.codeMetrics() != null
                    ? report.codeMetrics().duplicationPercent()
                    : 0.0;
            if (duplication > maxDuplication) {
                failures.add(String.format(Locale.ROOT,
                        "duplication %.1f%% exceeds --max-duplication %.1f%%.",
                        duplication, maxDuplication));
            }
        }
        if (minCoverage != null) {
            var coverage = report.coverageReport();
            if (coverage == null || !coverage.present()) {
                // A threshold of 0 means "measure but do not gate".
                if (minCoverage > 0) {
                    failures.add(String.format(Locale.ROOT,
                            "no line coverage data found, --min-coverage %.1f%% is not met.",
                            minCoverage));
                }
            } else if (coverage.lineCoveragePercent() < minCoverage) {
                failures.add(String.format(Locale.ROOT,
                        "line coverage %.1f%% is below --min-coverage %.1f%%.",
                        coverage.lineCoveragePercent(), minCoverage));
            }
        }
    }

    private static String reportOnlyReason(long errors, long warnings, long infos) {
        List<String> parts = new ArrayList<>();
        if (errors > 0) {
            parts.add(errors + " error-severity finding" + (errors == 1 ? "" : "s"));
        }
        if (warnings > 0) {
            parts.add(warnings + " warning" + (warnings == 1 ? "" : "s"));
        }
        if (errors == 0 && warnings == 0) {
            parts.add(infos + " info message" + (infos == 1 ? "" : "s"));
        }
        parts.add("report-only run (--fail-on NONE).");
        return String.join(", ", parts);
    }

    /** Prints the configured quality-gate thresholds and whether each one passed (progress channel). */
    private void printQualityGate(AnalysisReport report) {
        if (minCoverage == null && maxDuplication == null && maxWarnings == null) {
            return;
        }
        progress(SEPARATOR);
        progress(" QUALITY GATE VERIFICATION");
        progress(SEPARATOR);
        long warnings = report.countBySeverity(Severity.WARNING);
        checkMaxWarnings(warnings);
        checkDuplication(report);
        checkLineCoverage(report);
        progress(SEPARATOR);
    }

    private boolean checkMaxWarnings(long warnings) {
        if (maxWarnings == null) {
            return true;
        }
        boolean pass = warnings <= maxWarnings;
        progressf("  - Max Warnings:   %,d (Threshold: <= %,d) [%s]%n",
                warnings, maxWarnings, pass ? "PASS" : "FAIL");
        return pass;
    }

    private boolean checkDuplication(AnalysisReport report) {
        if (maxDuplication == null) {
            return true;
        }
        double duplication = report.codeMetrics() != null
                ? report.codeMetrics().duplicationPercent()
                : 0.0;
        boolean pass = duplication <= maxDuplication;
        progressf("  - Duplication:    %.1f%% (Threshold: <= %.1f%%) [%s]%n",
                duplication, maxDuplication, pass ? "PASS" : "FAIL");
        return pass;
    }

    private boolean checkLineCoverage(AnalysisReport report) {
        if (minCoverage == null) {
            return true;
        }
        var coverage = report.coverageReport();
        if (coverage == null || !coverage.present()) {
            // A threshold of 0 means "measure but do not gate": absence of coverage data is
            // not a failure in that case.
            boolean pass = minCoverage <= 0;
            progressf("  - Line Coverage:  Not detected (Threshold: >= %.1f%%) [%s]%n",
                    minCoverage, pass ? "PASS" : "FAIL");
            return pass;
        }
        boolean pass = coverage.lineCoveragePercent() >= minCoverage;
        progressf("  - Line Coverage:  %.1f%% (Threshold: >= %.1f%%) [%s]%n",
                coverage.lineCoveragePercent(), minCoverage, pass ? "PASS" : "FAIL");
        return pass;
    }

    /** True when the {@code --fail-on} severity threshold is exceeded by this report. */
    private boolean failOnExceeded(AnalysisReport report, long warnings) {
        if (failOn == null || failOn.isBlank()) {
            return false;
        }
        String target = failOn.trim().toUpperCase(Locale.ROOT);
        // WARNING and INFO both fail the build; they differ only in the severities considered.
        if ("WARNING".equals(target) && warnings > 0) {
            return true;
        }
        return "INFO".equals(target) && (warnings > 0 || report.countBySeverity(Severity.INFO) > 0);
    }
}
