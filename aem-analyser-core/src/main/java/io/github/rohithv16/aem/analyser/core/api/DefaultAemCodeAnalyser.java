package io.github.rohithv16.aem.analyser.core.api;

import io.github.rohithv16.aem.analyser.core.discovery.HtlScanner;
import io.github.rohithv16.aem.analyser.core.discovery.JcrComponentScanner;
import io.github.rohithv16.aem.analyser.core.discovery.OsgiConfigScanner;
import io.github.rohithv16.aem.analyser.core.discovery.ProjectDiscoveryEngine;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleClassification;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor;
import io.github.rohithv16.aem.analyser.core.model.htl.CqComponentDefinition;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlUnsafeContextFinding;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlUsage;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.bytecode.DependencyBytecodeIndexer;
import io.github.rohithv16.aem.analyser.core.parser.config.OsgiConfigDefinition;
import io.github.rohithv16.aem.analyser.core.parser.config.OsgiConfigParser;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParseResult;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParser;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleCatalog;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Default orchestration engine: discovery -> parallel parsing -> graph build -> rule evaluation.
 * Implements the two-phase thread-confined ingestion pipeline end to end.
 */
public final class DefaultAemCodeAnalyser implements AemCodeAnalyser {


    private final List<RuleEvaluator> rules = RuleCatalog.ordered();

    @Override
    public AnalysisReport analyze(AnalysisContext context) throws AnalysisException {
        long start = System.nanoTime();
        Map<String, Long> phases = new LinkedHashMap<>();
        io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics =
                new io.github.rohithv16.aem.analyser.core.util.DiagnosticSink();
        // One pruned walk per run. Every scanner and every file-scanning rule consumes this set,
        // instead of each of them walking the whole root (and paying for node_modules) again.
        long phaseStart = System.nanoTime();
        io.github.rohithv16.aem.analyser.core.discovery.FileInventory inventory =
                io.github.rohithv16.aem.analyser.core.discovery.FileInventory.scan(
                        context.projectRoot(), diagnostics);
        try (ProjectDiscoveryEngine discovery = new ProjectDiscoveryEngine(inventory, diagnostics)) {
            ProjectDiscoveryEngine.DiscoveryResult discoveryResult =
                    discovery.discover(context.projectRoot(), context.activeProfiles());
            phases.put("discovery", elapsedMillis(phaseStart));

            // ── Phase A: parallel extraction (worker threads; ASTs freed immediately) ──
            phaseStart = System.nanoTime();
            List<JavaSourceParseResult> javaResults =
                    parseJavaSourcesParallel(context, inventory, discoveryResult.modules(), diagnostics);
            List<OsgiConfigDefinition> configs =
                    parseOsgiConfigs(context, discoveryResult.modules(), diagnostics);
            phases.put("parse", elapsedMillis(phaseStart));

            // ── Phase A2: HTL & Component scanning ──────────────────────────────
            phaseStart = System.nanoTime();
            var htlScanner = new HtlScanner();
            List<HtlUsage> htlUsages = new ArrayList<>();
            List<HtlUnsafeContextFinding> htlUnsafeContexts = new ArrayList<>();
            List<io.github.rohithv16.aem.analyser.core.model.htl.HtlIncludeRef> htlIncludes = new ArrayList<>();
            List<io.github.rohithv16.aem.analyser.core.model.htl.HtlResourceRef> htlResources = new ArrayList<>();
            var compScanner = new JcrComponentScanner();
            List<CqComponentDefinition> cqComponents = new ArrayList<>();
            for (ModuleDescriptor module : discoveryResult.modules()) {
                if (!mayContainContent(module.classification())) {
                    continue;
                }
                var htlResult = htlScanner.scan(inventory, module.moduleDirectory(), diagnostics,
                        context.maxFileSizeBytes());
                htlResult.usages().stream()
                        .filter(usage -> isChanged(context, usage.filePath()))
                        .forEach(htlUsages::add);
                htlResult.unsafeContexts().stream()
                        .filter(finding -> isChanged(context, finding.filePath()))
                        .forEach(htlUnsafeContexts::add);
                htlResult.includes().stream()
                        .filter(ref -> isChanged(context, ref.filePath()))
                        .forEach(htlIncludes::add);
                htlResult.resources().stream()
                        .filter(ref -> isChanged(context, ref.filePath()))
                        .forEach(htlResources::add);
                compScanner.scanDirectory(inventory, module.moduleDirectory(), diagnostics,
                                context.maxFileSizeBytes()).stream()
                        .filter(component -> isChanged(context, component.contentXml()))
                        .forEach(cqComponents::add);
            }
            phases.put("htl", elapsedMillis(phaseStart));

            // Dependency bytecode indexing
            phaseStart = System.nanoTime();
            DependencyBytecodeIndexer.DependencyIndex dependencyIndex = indexDependencies(context, diagnostics);

            // ── Phase B/C/D: thread-confined graph ingestion ─────────────────────
            ReferenceGraphBuilder builder = new ReferenceGraphBuilder();
            ReferenceGraphBuilder.GraphBuildResult buildResult = builder.build(new ReferenceGraphBuilder.GraphInput(
                    discoveryResult.modules(), javaResults, configs, dependencyIndex, context.projectRoot()));
            phases.put("graph", elapsedMillis(phaseStart));

            Set<String> exportedPatterns = new LinkedHashSet<>(buildResult.exportedPatterns());
            javaResults.forEach(java -> exportedPatterns.addAll(java.exportedPackages()));

            // ── Rule evaluation ───────────────────────────────────────────────────
            phaseStart = System.nanoTime();
            RuleContext ruleContext = new RuleContext(buildResult.graph(), buildResult,
                    Set.copyOf(exportedPatterns), dependencyIndex, context.ignoredServices(), context.projectRoot(),
                    htlUsages, cqComponents, htlUnsafeContexts, discoveryResult.modules(),
                    htlIncludes, htlResources, inventory);
            List<Violation> violations = new ArrayList<>();
            for (RuleEvaluator rule : rules) {
                violations.addAll(rule.evaluate(ruleContext));
            }
            violations.sort(Comparator.comparing(Violation::filePath)
                    .thenComparing(v -> v.region().startLine())
                    .thenComparing(Violation::ruleId));
            phases.put("rules", elapsedMillis(phaseStart));

            // ── Open-Source PMD CPD Duplicate Detection ──────────────────────────
            phaseStart = System.nanoTime();
            var pmdEngine = new io.github.rohithv16.aem.analyser.core.discovery.PmdCpdDuplicationEngine();
            var pmdResult = pmdEngine.analyze(context.projectRoot(), javaResults);
            violations.addAll(pmdResult.violations());
            var codeMetrics = pmdResult.metrics();
            phases.put("cpd", elapsedMillis(phaseStart));

            // ── Embedded SonarSource Java Analysis ───────────────────────────────
            // The Sonar pass consumes discovery's Java file set and the run's filters, so
            // --changed-only/--max-file-size mean the same thing here as in every other phase.
            if (context.enableSonar()) {
                phaseStart = System.nanoTime();
                var sonarScanner = new io.github.rohithv16.aem.analyser.core.sonar.SonarJavaScanner(
                        context.projectRoot(), sonarJavaFiles(context, inventory),
                        moduleClasspath(discoveryResult.modules()),
                        io.github.rohithv16.aem.analyser.core.sonar.SonarRuleRegistry
                                .createChecksFor(context.sonarRules()));
                violations.addAll(sonarScanner.scan().stream()
                        .filter(v -> !io.github.rohithv16.aem.analyser.core.sonar.SonarAemSuppressions
                                .isSuppressed(context.projectRoot(), v))
                        .toList());
                phases.put("sonar", elapsedMillis(phaseStart));
            }

            phaseStart = System.nanoTime();
            violations.sort(Comparator.comparing(Violation::filePath)
                    .thenComparing(v -> v.region().startLine())
                    .thenComparing(Violation::ruleId));
            violations = deduplicate(violations);
            // Annotate inline directives instead of dropping the findings: the actionable list is
            // split off below so gate counts and the exit code stay identical, while the accepted
            // ones stay in the report for exporters that can express a suppression (#17).
            violations = io.github.rohithv16.aem.analyser.core.baseline.InlineSuppressions
                    .annotate(context.projectRoot(), violations);
            List<Violation> acceptedByDirective = violations.stream()
                    .filter(Violation::isAccepted)
                    .toList();
            if (!acceptedByDirective.isEmpty()) {
                violations = violations.stream().filter(v -> !v.isAccepted()).toList();
            }
            phases.put("suppression", elapsedMillis(phaseStart));

            phaseStart = System.nanoTime();
            var coverageEngine = new io.github.rohithv16.aem.analyser.core.discovery.CoverageDiscoveryEngine();
            var coverageReport = coverageEngine.discover(context.projectRoot(), discoveryResult.modules());
            phases.put("coverage", elapsedMillis(phaseStart));

            long durationMillis = (System.nanoTime() - start) / 1_000_000;
            return new AnalysisReport(context.projectRoot(), scanStatistics(discoveryResult, buildResult, htlUsages.size(), cqComponents.size()),
                    violations, durationMillis, coverageReport, codeMetrics, diagnostics.diagnostics())
                    .withAcceptedViolations(acceptedByDirective)
                    .withPhaseMillis(phases);
        } catch (IOException | RuntimeException e) {
            throw new AnalysisException("Analysis failed: " + e.getMessage(), e);
        }
    }

    private static long elapsedMillis(long sinceNanos) {
        return (System.nanoTime() - sinceNanos) / 1_000_000;
    }

    /**
     * The Java files Sonar may analyse: the same inventory the AEM rules parse, minus test sources
     * (Sonar has always been main-source only), narrowed by {@code --changed-only} and the size cap.
     */
    private static List<Path> sonarJavaFiles(AnalysisContext context,
                                             io.github.rohithv16.aem.analyser.core.discovery.FileInventory inventory) {
        List<Path> files = new ArrayList<>();
        for (Path file : inventory.javaFiles()) {
            if (file.toString().replace('\\', '/').contains("/src/test/")) {
                continue;
            }
            if (!isChanged(context, file)) {
                continue;
            }
            try {
                if (Files.size(file) > context.maxFileSizeBytes()) {
                    continue;
                }
            } catch (IOException e) {
                continue;
            }
            files.add(file);
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }

    /** Reactor classpath for Sonar's symbol resolution, without a third walk of the project root. */
    private static List<java.io.File> moduleClasspath(List<ModuleDescriptor> modules) {
        List<java.io.File> classpath = new ArrayList<>();
        for (ModuleDescriptor module : modules) {
            Path classes = module.moduleDirectory().resolve("target/classes");
            if (Files.isDirectory(classes)) {
                classpath.add(classes.toFile());
            }
        }
        return classpath;
    }

    private List<JavaSourceParseResult> parseJavaSourcesParallel(AnalysisContext context,
                                                                 io.github.rohithv16.aem.analyser.core.discovery.FileInventory inventory,
                                                                 List<io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor> modules,
                                                                 io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics)
            throws IOException {
        List<Path> javaFiles = new ArrayList<>();
        for (ModuleDescriptor module : modules) {
            // Conventional Java sources plus Java hosted inside content packages (AEM archetypes set
            // sourceDirectory=jcr_root for ui.apps, e.g. servlet error handlers).
            List<Path> sourceRoots = List.of(
                    module.moduleDirectory().resolve("src/main/java"),
                    module.moduleDirectory().resolve("src/main/content/jcr_root"));
            for (Path sourceRoot : sourceRoots) {
                if (!Files.isDirectory(sourceRoot)) {
                    continue;
                }
                for (Path file : inventory.filesUnder(sourceRoot,
                        io.github.rohithv16.aem.analyser.core.discovery.FileInventory.withExtension(".java"))) {
                    try {
                        if (Files.size(file) > context.maxFileSizeBytes()) {
                            diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.FILE_TOO_LARGE,
                                    file, "exceeds " + (context.maxFileSizeBytes() / (1024 * 1024)) + " MiB");
                        } else if (isChanged(context, file)) {
                            javaFiles.add(file);
                        }
                    } catch (IOException e) {
                        diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.UNREADABLE_TREE,
                                file, io.github.rohithv16.aem.analyser.core.util.DiagnosticSink.reasonOf(e));
                    }
                }
            }
        }
        javaFiles.sort(Comparator.naturalOrder());

        JavaSourceParser parser = new JavaSourceParser();
        parser.setProjectConstants(scanStringConstants(javaFiles, context.maxFileSizeBytes()));
        List<JavaSourceParseResult> results = new ArrayList<>(javaFiles.size());
        long deadlineNanos = context.timeoutMillis() > 0
                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(context.timeoutMillis())
                : 0;
        ExecutorService workers = Executors.newFixedThreadPool(context.workerThreads());
        try {
            List<Future<JavaSourceParseResult>> futures = new ArrayList<>(javaFiles.size());
            for (Path javaFile : javaFiles) {
                futures.add(workers.submit(() -> parser.parseStrict(javaFile)));
            }
            for (int i = 0; i < futures.size(); i++) {
                Path javaFile = javaFiles.get(i);
                try {
                    if (deadlineNanos > 0) {
                        long remaining = deadlineNanos - System.nanoTime();
                        if (remaining <= 0) {
                            throw new java.util.concurrent.TimeoutException();
                        }
                        results.add(futures.get(i).get(remaining, TimeUnit.NANOSECONDS));
                    } else {
                        results.add(futures.get(i).get());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Java parsing interrupted", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.JAVA_PARSE_ERROR,
                            javaFile, io.github.rohithv16.aem.analyser.core.util.DiagnosticSink.reasonOf(e));
                } catch (java.util.concurrent.TimeoutException e) {
                    futures.forEach(future -> future.cancel(true));
                    diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.ANALYSIS_TIMEOUT,
                            context.projectRoot(), "analysis exceeded " + context.timeoutMillis() + " ms");
                    break;
                }
            }
        } finally {
            workers.shutdown();
            try {
                if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                    workers.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return results;
    }

    private List<OsgiConfigDefinition> parseOsgiConfigs(AnalysisContext context,
                                                        List<io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor> modules,
                                                        io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics) {
        OsgiConfigScanner scanner = new OsgiConfigScanner();
        OsgiConfigParser parser = new OsgiConfigParser();
        io.github.rohithv16.aem.analyser.core.util.PathPatternMatcher ignoredConfigs =
                new io.github.rohithv16.aem.analyser.core.util.PathPatternMatcher(
                        context.projectRoot(), context.ignoredConfigPaths());
        List<OsgiConfigDefinition> configs = new ArrayList<>();
        for (ModuleDescriptor module : modules) {
            if (module.osgiConfigPaths().isEmpty()) {
                continue;
            }
            for (Path configPath : module.osgiConfigPaths()) {
                if (ignoredConfigs.matches(configPath)) {
                    continue;
                }
                if (!isChanged(context, configPath)) {
                    continue;
                }
                try {
                    if (Files.size(configPath) > context.maxFileSizeBytes()) {
                        diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.FILE_TOO_LARGE,
                                configPath, "exceeds " + (context.maxFileSizeBytes() / (1024 * 1024)) + " MiB");
                        continue;
                    }
                } catch (IOException e) {
                    diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.UNREADABLE_TREE,
                            configPath, io.github.rohithv16.aem.analyser.core.util.DiagnosticSink.reasonOf(e));
                    continue;
                }
                Set<String> runmodes = scanner.resolveRunmodes(
                        configPath.getParent().getFileName().toString());
                try {
                    configs.add(parser.parse(configPath, runmodes));
                } catch (IOException | RuntimeException e) {
                    diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.CONFIG_PARSE_ERROR,
                            configPath, io.github.rohithv16.aem.analyser.core.util.DiagnosticSink.reasonOf(e));
                }
            }
        }
        return configs;
    }

    /**
     * Cheap pre-scan for {@code static final String NAME = "literal";} so annotation values that
     * reference a constant from another compilation unit can be folded (e.g. {@code configurationPid
     * = Consts.PID}).
     *
     * <p>Constants are indexed by simple name, {@code Type.NAME} and {@code package.Type.NAME}. A
     * simple name declared twice with different values is dropped so it can never resolve to the
     * wrong class; the qualified keys keep working.
     */
    private static Map<String, String> scanStringConstants(List<Path> javaFiles, long maxFileSizeBytes) {
        java.util.regex.Pattern constantPattern = java.util.regex.Pattern.compile(
                "static\\s+final\\s+String\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;");
        java.util.regex.Pattern packagePattern = java.util.regex.Pattern.compile(
                "^\\s*package\\s+([\\w.]+)\\s*;");
        java.util.regex.Pattern typePattern = java.util.regex.Pattern.compile(
                "\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");

        Map<String, String> constants = new LinkedHashMap<>();
        Set<String> ambiguousSimpleNames = new HashSet<>();

        for (Path file : javaFiles) {
            try {
                if (Files.size(file) > maxFileSizeBytes) {
                    continue;
                }
                String source = Files.readString(file);
                String packageName = "";
                String typeName = "";
                for (String line : source.split("\r?\n")) {
                    java.util.regex.Matcher packageMatcher = packagePattern.matcher(line);
                    if (packageMatcher.find()) {
                        packageName = packageMatcher.group(1);
                    }
                    java.util.regex.Matcher typeMatcher = typePattern.matcher(line);
                    if (typeMatcher.find()) {
                        typeName = typeMatcher.group(1);
                    }
                    java.util.regex.Matcher constantMatcher = constantPattern.matcher(line);
                    while (constantMatcher.find()) {
                        String name = constantMatcher.group(1);
                        String value = constantMatcher.group(2);
                        String existing = constants.get(name);
                        if (existing != null && !existing.equals(value)) {
                            ambiguousSimpleNames.add(name);
                        } else {
                            constants.putIfAbsent(name, value);
                        }
                        if (!typeName.isEmpty()) {
                            constants.putIfAbsent(typeName + "." + name, value);
                        }
                        if (!packageName.isEmpty() && !typeName.isEmpty()) {
                            constants.putIfAbsent(packageName + "." + typeName + "." + name, value);
                        }
                    }
                }
            } catch (IOException e) {
                // unreadable file: reported by the parse phase
            }
        }
        ambiguousSimpleNames.forEach(constants::remove);
        return constants;
    }

    private DependencyBytecodeIndexer.DependencyIndex indexDependencies(AnalysisContext context,
                                                                        io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics) {
        DependencyBytecodeIndexer indexer = new DependencyBytecodeIndexer();
        List<Path> jars = new ArrayList<>();
        for (Path root : context.dependencyJarRoots()) {
            if (Files.isRegularFile(root) && root.toString().endsWith(".jar")) {
                jars.add(root);
            } else if (Files.isDirectory(root)) {
                try (Stream<Path> stream = Files.walk(root)) {
                    stream.filter(f -> f.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                          .sorted()
                          .forEach(jars::add);
                } catch (IOException e) {
                    diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.DEPENDENCY_INDEX,
                            root, io.github.rohithv16.aem.analyser.core.util.DiagnosticSink.reasonOf(e));
                }
            }
        }
        return indexer.index(jars, diagnostics);
    }

    /**
     * Changed-only fast path: when a filter is supplied, files outside it are never parsed.
     */
    private static boolean isChanged(AnalysisContext context, Path file) {
        if (context.changedFiles().isEmpty() || file == null) {
            return true;
        }
        String relative = context.projectRoot().relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
        for (String candidate : context.changedFiles()) {
            String normalized = candidate.replace('\\', '/');
            if (relative.equals(normalized) || relative.endsWith("/" + normalized)
                    || normalized.endsWith("/" + relative)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Only content-bearing modules may contain HTL templates or JCR component definitions. Scanning
     * an aggregator (especially the reactor root) would walk child modules again and duplicate every
     * HTL/component finding.
     */
    private static boolean mayContainContent(ModuleClassification classification) {
        return switch (classification) {
            case APPS_MODULE, CONTENT_MODULE, CONTAINER_MODULE, STRUCTURE_MODULE, ACL_MODULE,
                 UNCLASSIFIED -> true;
            default -> false;
        };
    }

    /**
     * Collapses identical findings. Producers are supposed to be unique, but this safety net keeps
     * reports honest if discovery overlaps or two engines ever report the same location.
     */
    private static List<Violation> deduplicate(List<Violation> violations) {
        Map<String, Violation> unique = new LinkedHashMap<>();
        for (Violation violation : violations) {
            String key = violation.ruleId() + "|" + violation.filePath() + "|"
                    + (violation.region() == null ? "0" : violation.region().startLine()) + "|"
                    + (violation.region() == null ? "0" : violation.region().startColumn()) + "|"
                    + violation.subtype();
            unique.putIfAbsent(key, violation);
        }
        return new ArrayList<>(unique.values());
    }

    private Map<String, Integer> scanStatistics(ProjectDiscoveryEngine.DiscoveryResult discoveryResult,
                                                ReferenceGraphBuilder.GraphBuildResult buildResult,
                                                int htlUsagesCount,
                                                int cqComponentsCount) {
        Map<String, Integer> stats = new LinkedHashMap<>();
        List<ModuleDescriptor> modules = discoveryResult.modules();
        stats.put("modules", modules.size());
        stats.put("bundles", (int) modules.stream()
                .filter(m -> m.classification() == ModuleClassification.BUNDLE_MODULE).count());
        stats.put("cloudConfigs", discoveryResult.cloudConfigs().size());
        stats.put("osgiConfigs", buildResult.configNodes().size());
        stats.put("components", buildResult.componentsByFqn().size());
        stats.put("cqComponents", cqComponentsCount);
        stats.put("slingModels", buildResult.modelNodes().size());
        stats.put("htlUsages", htlUsagesCount);
        stats.put("codeBlocks", buildResult.codeBlocks().size());
        stats.put("edges", buildResult.resolvedEdges().size());
        return stats;
    }
}
