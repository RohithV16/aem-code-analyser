package io.github.rohithv16.aem.analyser.cli;

import io.github.rohithv16.aem.analyser.core.api.AnalysisContext;
import io.github.rohithv16.aem.analyser.core.api.AemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.api.AnalysisException;
import io.github.rohithv16.aem.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.export.ConsoleReportFormatter;
import io.github.rohithv16.aem.analyser.core.export.ExportException;
import io.github.rohithv16.aem.analyser.core.export.HtmlReportExporter;
import io.github.rohithv16.aem.analyser.core.export.ReportFormat;
import io.github.rohithv16.aem.analyser.core.export.SarifReportExporter;
import io.github.rohithv16.aem.analyser.core.export.SonarReportExporter;
import io.github.rohithv16.aem.analyser.core.fix.AutoFixEngine;
import io.github.rohithv16.aem.analyser.core.git.GitDiffResolver;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * FEAT-DELIV-02: standalone Picocli command-line executable.
 * Designed for sub-second developer inner-loop scans, pre-commit hooks and
 * standalone audits of unbuilt repositories.
 */
@Command(name = "aem-analyser",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "AEM Code Analyser — cross-boundary dead code detection engine "
                + "(Phase 1: Java & OSGi configurations).")
public class AnalyserCli implements Runnable {

    @Option(names = {"-p", "--project-root"},
            description = "Project (reactor) root directory. Defaults to the current directory, "
                    + "auto-discovering the enclosing reactor root.")
    private Path projectRoot;

    @Parameters(index = "0", arity = "0..1", paramLabel = "PROJECT_ROOT",
            description = "Project root, as a shorthand for --project-root "
                    + "(e.g. 'aem-analyser .' inside the project).")
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
            description = "Output directory for SARIF/SONAR/HTML files. Default: ./target/aem-analyser")
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
            description = "Print structured analysis diagnostics (unparseable files, skipped trees).")
    private boolean verbose;

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
            description = "List every rule with severity and effort, then exit.")
    private boolean listRules;

    @Option(names = {"--explain"}, paramLabel = "RULE",
            description = "Print metadata for one rule (e.g. AEM-DEAD-002A), then exit.")
    private String explainRule;

    @Option(names = {"--generate-rule-docs"}, paramLabel = "DIR",
            description = "Generate Markdown documentation for every rule into DIR, then exit.")
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

    private final PrintWriter stdout = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

    @Override
    public void run() {
        System.exit(execute());
    }

    int execute() {
        if (generateRuleDocs != null) {
            try {
                io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDocsGenerator
                        .writeAll(generateRuleDocs);
                stdout.println("[INFO] Rule documentation written to " + generateRuleDocs);
                return 0;
            } catch (IOException e) {
                stdout.println("[ERROR] Could not write rule documentation: " + e.getMessage());
                return 2;
            }
        }
        if (listRules) {
            printRules(stdout);
            return 0;
        }
        if (explainRule != null && !explainRule.isBlank()) {
            return printExplain(stdout, explainRule);
        }
        try {
            Path root = resolveProjectRoot();
            List<ReportFormat> reportFormats = parseFormats();
            validateFailOn();
            validateNumericOptions();
            if (collectCoverage) {
                runCoverageCollection(root);
            }
            AnalysisContext.Builder builder = AnalysisContext.builder(root);
            io.github.rohithv16.aem.analyser.core.config.AnalyserConfig config;
            try {
                config = configFile != null
                        ? io.github.rohithv16.aem.analyser.core.config.AnalyserConfigLoader.load(configFile)
                        : io.github.rohithv16.aem.analyser.core.config.AnalyserConfigLoader.loadIfPresent(root);
            } catch (IOException e) {
                stdout.println("[ERROR] Could not read configuration: " + e.getMessage());
                return 2;
            }
            java.util.Set<String> effectiveIgnoredServices = new java.util.LinkedHashSet<>(
                    ignoredServices == null ? java.util.Set.of() : ignoredServices);
            effectiveIgnoredServices.addAll(config.allowlists().services());
            java.util.Set<String> effectiveIgnoredConfigs = new java.util.LinkedHashSet<>(
                    ignoredConfigPaths == null ? java.util.Set.of() : ignoredConfigPaths);
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
            boolean enableSonar = sonar;
            builder = builder.enableSonar(enableSonar);
            if (sonarRules != null && !sonarRules.isBlank()) {
                builder = builder.sonarRules(sonarRules);
            }

            AnalysisContext context = builder.build();
            if (changedOnly || (since != null && !since.isBlank())) {
                var diff = new GitDiffResolver().resolveChanged(root, since);
                if (diff.failed()) {
                    // Never shrink the parsed file set from a failed diff: a typo in --since (or an
                    // unfetched CI base ref) would otherwise report a clean build.
                    stdout.println("[WARN] " + diff.failureReason());
                    stdout.println("[WARN] Falling back to a FULL scan so findings cannot be dropped.");
                } else if (diff.isGitRepo()) {
                    context = context.toBuilder().changedFiles(diff.changedFiles()).build();
                }
            }
            AnalysisReport report = analyzeAndFilter(context, root);

            if (fix || fixDryRun) {
                AutoFixEngine fixEngine = new AutoFixEngine();
                var fixSummary = fixEngine.fix(root, report.violations(), fixDryRun);
                if (fixSummary.hasFixes()) {
                    stdout.println(String.format("[INFO] %s %d issue(s) across %d file(s):",
                            fixDryRun ? "Auto-fix dry-run: would repair" : "Auto-fix successfully repaired",
                            fixSummary.fixesApplied(), fixSummary.filesModified()));
                    for (var r : fixSummary.records()) {
                        stdout.println("  - [" + r.ruleId() + "] " + r.filePath() + ": " + r.description());
                    }
                    // A real fix changed the sources: the pre-fix report would otherwise drive a
                    // stale console output, export files and exit code.
                    if (!fixDryRun) {
                        stdout.println("[INFO] Re-analysing after auto-fix...");
                        report = analyzeAndFilter(context, root);
                    }
                } else {
                    stdout.println("[INFO] No auto-fixable code smells detected in target files.");
                }
                if (!fixSummary.writeFailures().isEmpty()) {
                    for (String failure : fixSummary.writeFailures()) {
                        stdout.println("[WARNING] Could not auto-fix " + failure);
                    }
                }
            }

            report = config.apply(report);
            report = applyBaseline(root, report);
            List<io.github.rohithv16.aem.analyser.core.history.RunSummary> history = applyHistory(report);
            export(report, reportFormats, history);
            printDiagnostics(report);
            return exitCode(report);
        } catch (AnalysisException e) {
            stdout.println("[ERROR] Analysis failed: " + e.getMessage());
            return 2;
        } catch (IOException | ExportException e) {
            stdout.println("[ERROR] Report export failed: " + e.getMessage());
            return 2;
        } catch (IllegalArgumentException e) {
            stdout.println("[ERROR] " + e.getMessage());
            return 2;
        }
    }

    private AnalysisReport applyBaseline(Path root, AnalysisReport report) throws IOException {
        Path path = baseline;
        Path defaultPath = root.resolve(".aem-analyser-baseline.json");
        if (updateBaseline) {
            if (path == null) {
                path = defaultPath;
            }
            io.github.rohithv16.aem.analyser.core.baseline.BaselineStore.save(path, report);
            stdout.println("[INFO] Baseline written to " + path + " ("
                    + report.violations().size() + " finding(s) accepted).");
        } else if (path == null && Files.isRegularFile(defaultPath)) {
            path = defaultPath;
        }
        if (path == null) {
            return report;
        }
        if (!Files.isRegularFile(path)) {
            if (!updateBaseline) {
                stdout.println("[WARNING] Baseline file not found: " + path);
            }
            return report;
        }
        io.github.rohithv16.aem.analyser.core.baseline.Baseline loaded =
                io.github.rohithv16.aem.analyser.core.baseline.BaselineStore.load(path);
        int suppressed = (int) report.violations().stream()
                .filter(v -> loaded.contains(io.github.rohithv16.aem.analyser.core.baseline.Fingerprint.of(v)))
                .count();
        AnalysisReport filtered = io.github.rohithv16.aem.analyser.core.baseline.BaselineFilter
                .filter(report, loaded, baselineReport);
        stdout.println("[INFO] Baseline suppressed " + suppressed + " accepted finding(s).");
        return filtered;
    }

    private void printDiagnostics(AnalysisReport report) {
        if (!verbose || report.diagnostics().isEmpty()) {
            return;
        }
        stdout.println("[INFO] Analysis diagnostics (" + report.diagnostics().size() + "):");
        for (var diagnostic : report.diagnostics()) {
            String location = diagnostic.line() > 0
                    ? diagnostic.path() + ":" + diagnostic.line()
                    : diagnostic.path();
            stdout.println("  - [" + diagnostic.kind() + "] " + location + ": " + diagnostic.reason());
        }
    }

    void printRules(PrintWriter out) {
        out.println("AEM Code Analyser rules:");
        for (var rule : io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry.all()) {
            out.printf("  %-16s %-8s %3dm  %s%n",
                    rule.id(), rule.defaultSeverity(), rule.effortMinutes(), rule.shortDescription());
        }
        out.flush();
    }

    int printExplain(PrintWriter out, String ruleId) {
        var descriptor = io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry.find(ruleId);
        if (descriptor.isEmpty()) {
            out.println("[ERROR] Unknown rule '" + ruleId + "'. Use --list-rules to see all rules.");
            out.flush();
            return 2;
        }
        var rule = descriptor.get();
        out.println(rule.id() + " — " + rule.name());
        out.println("  Severity:     " + rule.defaultSeverity());
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
                stdout.println("[WARN] " + diffResult.failureReason());
                stdout.println("[WARN] Git-diff filtering disabled for this run: reporting every finding"
                        + " from a full scan.");
            } else if (diffResult.isGitRepo()) {
                if (diffResult.changedFiles().isEmpty()) {
                    stdout.println("[WARN] Git-diff filtering is active but NO changed files were detected."
                            + " Every finding is filtered out and this run cannot fail on content."
                            + " Drop --changed-only/--since for a full scan.");
                }
                stdout.println(String.format("[INFO] Git-diff filtering active (%d changed file(s) found); scoping violations to changed files and lines.",
                        diffResult.changedFiles().size()));
                List<Violation> filtered = report.violations().stream()
                        .filter(v -> diffResult.matches(v.filePath(), v.region() != null ? v.region().startLine() : 0))
                        .toList();
                report = report.withViolations(filtered);
            } else {
                stdout.println("[INFO] Directory is not a git repository or git command failed; proceeding with full scan.");
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
        Path candidate = Files.isRegularFile(start.resolve("pom.xml")) ? start : enclosingPomRoot(start);
        for (int depth = 0; depth < MAX_REACTOR_DEPTH && isLocalModulePom(candidate); depth++) {
            candidate = candidate.getParent();
        }
        return candidate;
    }

    private static final int MAX_REACTOR_DEPTH = 8;

    /** The nearest ancestor (or the start itself) that contains a POM. */
    private static Path enclosingPomRoot(Path from) {
        Path current = from;
        for (int i = 0; i < 6 && current.getParent() != null; i++) {
            current = current.getParent();
            if (Files.isRegularFile(current.resolve("pom.xml"))) {
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
        Path pom = dir.resolve("pom.xml");
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
                case CONSOLE -> new ConsoleReportFormatter(!noColor).export(report, stdout);
                case SARIF -> {
                    Path target = outputTarget("aem-analyser.sarif");
                    try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                        new SarifReportExporter().export(report, writer);
                    }
                    stdout.println("SARIF report written to " + target);
                }
                case SONAR -> {
                    Path target = outputTarget("sonar-aem-analyser-report.json");
                    try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                        new SonarReportExporter().export(report, writer);
                    }
                    stdout.println("SonarQube report written to " + target);
                }
                case HTML -> writeHtmlReport(report, history, htmlTarget());
                case GITHUB -> {
                    Path target = outputTarget("github-annotations.txt");
                    try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                        new io.github.rohithv16.aem.analyser.core.export.GitHubAnnotationExporter()
                                .export(report, writer);
                    }
                    stdout.println("GitHub annotations written to " + target);
                }
                case GITLAB -> {
                    Path target = outputTarget("gitlab-code-quality.json");
                    try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                        new io.github.rohithv16.aem.analyser.core.export.GitLabCodeQualityExporter()
                                .export(report, writer);
                    }
                    stdout.println("GitLab Code Quality report written to " + target);
                }
                case AZURE -> {
                    Path target = outputTarget("azure-pipelines.log");
                    try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                        new io.github.rohithv16.aem.analyser.core.export.AzurePipelinesExporter()
                                .export(report, writer);
                    }
                    stdout.println("Azure Pipelines annotations written to " + target);
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
        stdout.println("HTML report written to " + target);
    }

    private Path outputTarget(String fileName) throws IOException {
        Path target = outputDirectory != null ? outputDirectory
                : Path.of("target/aem-analyser");
        Path resolved = Files.createDirectories(target.toAbsolutePath().normalize());
        return resolved.resolve(fileName);
    }

    private void runCoverageCollection(Path root) {
        List<String> testModules = findJavaTestModules(root);
        CoverageStatus status = checkCoverageStatus(root, testModules);

        if (!force && status.isFresh()) {
            String rel = root.relativize(status.coverageFile()).toString();
            stdout.println("[INFO] Existing JaCoCo coverage is up to date (" + rel + "); skipping test execution (use --force to re-run).");
            return;
        }

        if (force) {
            stdout.println("[INFO] --force specified: executing unit tests to collect fresh test code coverage...");
        } else if (status.coverageFile() != null) {
            stdout.println("[INFO] " + status.reason() + "; re-executing unit tests for fresh coverage...");
        } else {
            stdout.println("[INFO] Dynamic JaCoCo enabled: executing unit tests to collect test code coverage...");
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
                stdout.println("[WARNING] Maven test execution returned code " + exitCode + "; continuing analysis with available artifacts.");
            } else {
                stdout.println("[INFO] Test coverage collected successfully.");
            }
        } catch (IOException e) {
            stdout.println("[WARNING] Unable to run Maven to collect coverage: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stdout.println("[WARNING] Coverage collection interrupted.");
        }
    }

    record CoverageStatus(Path coverageFile, boolean isFresh, String reason) {}

    CoverageStatus checkCoverageStatus(Path root, List<String> testModules) {
        if (testModules.isEmpty()) {
            Path existing = findExistingCoverage(root);
            if (existing == null) {
                return new CoverageStatus(null, false, "No JaCoCo coverage file found");
            }
            try {
                FileTime covTime = Files.getLastModifiedTime(existing);
                Path newer = findNewerSourceFileInDir(root.resolve("src"), covTime);
                if (newer != null) {
                    return new CoverageStatus(existing, false, "Source modified (" + root.relativize(newer) + ")");
                }
                return new CoverageStatus(existing, true, "Coverage is up to date");
            } catch (IOException e) {
                return new CoverageStatus(existing, false, "Timestamp error");
            }
        }

        Path sampleCoverage = null;
        for (String modRel : testModules) {
            Path modDir = root.resolve(modRel);
            Path cov = findExistingCoverage(modDir);
            if (cov == null) {
                return new CoverageStatus(null, false, "Missing coverage in module " + modRel);
            }
            sampleCoverage = cov;
            try {
                FileTime covTime = Files.getLastModifiedTime(cov);
                Path modPom = modDir.resolve("pom.xml");
                if (Files.isRegularFile(modPom) && Files.getLastModifiedTime(modPom).compareTo(covTime) > 0) {
                    return new CoverageStatus(cov, false, "Module POM modified (" + root.relativize(modPom) + ")");
                }
                Path rootPom = root.resolve("pom.xml");
                if (Files.isRegularFile(rootPom) && Files.getLastModifiedTime(rootPom).compareTo(covTime) > 0) {
                    return new CoverageStatus(cov, false, "Root pom.xml modified");
                }
                Path newer = findNewerSourceFileInDir(modDir.resolve("src"), covTime);
                if (newer != null) {
                    return new CoverageStatus(cov, false, "Source modified (" + root.relativize(newer) + ")");
                }
            } catch (IOException e) {
                return new CoverageStatus(cov, false, "Timestamp error in module " + modRel);
            }
        }
        return new CoverageStatus(sampleCoverage, true, "All module coverage artifacts are up to date");
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

    private List<String> resolveMavenCommand(Path root) {
        return resolveMavenCommand(root, findJavaTestModules(root));
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
        Path pomPath = root.resolve("pom.xml");
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
            return stream.filter(p -> p.getFileName().toString().equals("pom.xml"))
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
            stream.filter(p -> p.getFileName().toString().equals("pom.xml") && !p.equals(root.resolve("pom.xml")))
                    .forEach(pom -> {
                        Path moduleDir = pom.getParent();
                        if (Files.isDirectory(moduleDir.resolve("src/test/java"))) {
                            try {
                                String rel = root.relativize(moduleDir).toString();
                                if (!rel.isEmpty() && !modules.contains(rel)) {
                                    modules.add(rel);
                                }
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    });
        } catch (IOException ignored) {
        }
        return modules;
    }

    int exitCode(AnalysisReport report) {
        long errors = report.countBySeverity(Severity.ERROR);
        long warnings = report.countBySeverity(Severity.WARNING);
        boolean qualityGateFailed = false;

        boolean hasQualityGate = minCoverage != null || maxDuplication != null || maxWarnings != null;
        if (hasQualityGate) {
            stdout.println("--------------------------------------------------------------------------------");
            stdout.println(" QUALITY GATE VERIFICATION");
            stdout.println("--------------------------------------------------------------------------------");

            if (maxWarnings != null) {
                boolean pass = warnings <= maxWarnings;
                if (!pass) qualityGateFailed = true;
                stdout.println(String.format("  - Max Warnings:   %,d (Threshold: <= %,d) [%s]",
                        warnings, maxWarnings, pass ? "PASS" : "FAIL"));
            }

            if (maxDuplication != null) {
                double dup = report.codeMetrics() != null ? report.codeMetrics().duplicationPercent() : 0.0;
                boolean pass = dup <= maxDuplication;
                if (!pass) qualityGateFailed = true;
                stdout.println(String.format("  - Duplication:    %.1f%% (Threshold: <= %.1f%%) [%s]",
                        dup, maxDuplication, pass ? "PASS" : "FAIL"));
            }

            if (minCoverage != null) {
                var cov = report.coverageReport();
                if (cov == null || !cov.present()) {
                    // A threshold of 0 means "measure but do not gate": absence of coverage data is
                    // not a failure in that case.
                    boolean pass = minCoverage <= 0;
                    if (!pass) {
                        qualityGateFailed = true;
                    }
                    stdout.println(String.format("  - Line Coverage:  Not detected (Threshold: >= %.1f%%) [%s]",
                            minCoverage, pass ? "PASS" : "FAIL"));
                } else {
                    boolean pass = cov.lineCoveragePercent() >= minCoverage;
                    if (!pass) qualityGateFailed = true;
                    stdout.println(String.format("  - Line Coverage:  %.1f%% (Threshold: >= %.1f%%) [%s]",
                            cov.lineCoveragePercent(), minCoverage, pass ? "PASS" : "FAIL"));
                }
            }
            stdout.println("--------------------------------------------------------------------------------");
        }

        boolean reportOnly = failOn != null && "NONE".equalsIgnoreCase(failOn.trim());
        if (qualityGateFailed) {
            return 1;
        }
        if (reportOnly) {
            // Explicit report-only mode: thresholds above still gate, the implicit ERROR gate does not.
            stdout.println("[INFO] --fail-on NONE: report-only run; exiting 0 despite "
                    + errors + " error-severity finding(s).");
            return 0;
        }
        if (errors > 0) {
            return 1;
        }
        if (failOn != null && !failOn.isBlank()) {
            String target = failOn.trim().toUpperCase(Locale.ROOT);
            if ("WARNING".equals(target) && warnings > 0) {
                return 1;
            } else if ("INFO".equals(target) && (warnings > 0 || report.countBySeverity(Severity.INFO) > 0)) {
                return 1;
            }
        }
        if (failOnWarning && warnings > 0) {
            return 1;
        }
        return 0;
    }

    public static void main(String[] args) {
        new CommandLine(new AnalyserCli()).execute(args);
    }
}
