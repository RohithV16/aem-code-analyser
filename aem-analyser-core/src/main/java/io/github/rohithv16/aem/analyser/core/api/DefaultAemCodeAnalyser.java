package io.github.rohithv16.aem.analyser.core.api;

import io.github.rohithv16.aem.analyser.core.discovery.HtlScanner;
import io.github.rohithv16.aem.analyser.core.discovery.JcrComponentScanner;
import io.github.rohithv16.aem.analyser.core.discovery.OsgiConfigScanner;
import io.github.rohithv16.aem.analyser.core.discovery.ProjectDiscoveryEngine;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleClassification;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor;
import io.github.rohithv16.aem.analyser.core.model.htl.CqComponentDefinition;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlUsage;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.bytecode.DependencyBytecodeIndexer;
import io.github.rohithv16.aem.analyser.core.parser.config.OsgiConfigDefinition;
import io.github.rohithv16.aem.analyser.core.parser.config.OsgiConfigParser;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParseResult;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParser;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemComp001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead002ARule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead002BRule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead003ARule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead003BRule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead003CRule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead004Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDup001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDup002Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDup003Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDup010Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemPerf001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemPerf002Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemPerf003Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual002Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual003Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual004Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual005Rule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    private final List<RuleEvaluator> rules = List.of(
            new AemDead001Rule(),
            new AemDead002ARule(),
            new AemDead002BRule(),
            new AemDead003ARule(),
            new AemDead003BRule(),
            new AemDead003CRule(),
            new AemDead004Rule(),
            new AemComp001Rule(),
            new AemDup001Rule(),
            new AemDup002Rule(),
            new AemDup003Rule(),
            new AemQual001Rule(),
            new AemQual002Rule(),
            new AemQual003Rule(),
            new AemQual004Rule(),
            new AemQual005Rule(),
            new AemPerf001Rule(),
            new AemPerf002Rule(),
            new AemPerf003Rule());

    @Override
    public AnalysisReport analyze(AnalysisContext context) throws AnalysisException {
        long start = System.nanoTime();
        try (ProjectDiscoveryEngine discovery = new ProjectDiscoveryEngine()) {
            ProjectDiscoveryEngine.DiscoveryResult discoveryResult =
                    discovery.discover(context.projectRoot(), context.activeProfiles());

            // ── Phase A: parallel extraction (worker threads; ASTs freed immediately) ──
            List<JavaSourceParseResult> javaResults = parseJavaSourcesParallel(context, discoveryResult.modules());
            List<OsgiConfigDefinition> configs = parseOsgiConfigs(context, discoveryResult.modules());

            // ── Phase A2: HTL & Component scanning ──────────────────────────────
            var htlScanner = new HtlScanner();
            List<HtlUsage> htlUsages = new ArrayList<>();
            var compScanner = new JcrComponentScanner();
            List<CqComponentDefinition> cqComponents = new ArrayList<>();
            for (ModuleDescriptor module : discoveryResult.modules()) {
                htlUsages.addAll(htlScanner.scanDirectory(module.moduleDirectory()));
                cqComponents.addAll(compScanner.scanDirectory(module.moduleDirectory()));
            }

            // Dependency bytecode indexing
            DependencyBytecodeIndexer.DependencyIndex dependencyIndex = indexDependencies(context);

            // ── Phase B/C/D: thread-confined graph ingestion ─────────────────────
            ReferenceGraphBuilder builder = new ReferenceGraphBuilder();
            ReferenceGraphBuilder.GraphBuildResult buildResult = builder.build(new ReferenceGraphBuilder.GraphInput(
                    discoveryResult.modules(), javaResults, configs, dependencyIndex, context.projectRoot()));

            Set<String> exportedPatterns = new LinkedHashSet<>(buildResult.exportedPatterns());
            javaResults.forEach(java -> exportedPatterns.addAll(java.exportedPackages()));

            // ── Rule evaluation ───────────────────────────────────────────────────
            RuleContext ruleContext = new RuleContext(buildResult.graph(), buildResult,
                    Set.copyOf(exportedPatterns), dependencyIndex, context.ignoredServices(), context.projectRoot(),
                    htlUsages, cqComponents);
            List<Violation> violations = new ArrayList<>();
            for (RuleEvaluator rule : rules) {
                violations.addAll(rule.evaluate(ruleContext));
            }
            violations.sort(Comparator.comparing(Violation::filePath)
                    .thenComparing(v -> v.region().startLine())
                    .thenComparing(Violation::ruleId));

            // ── Open-Source PMD CPD Duplicate Detection ──────────────────────────
            var pmdEngine = new io.github.rohithv16.aem.analyser.core.discovery.PmdCpdDuplicationEngine();
            var pmdResult = pmdEngine.analyze(context.projectRoot(), javaResults);
            violations.addAll(pmdResult.violations());
            var codeMetrics = pmdResult.metrics();

            violations.sort(Comparator.comparing(Violation::filePath)
                    .thenComparing(v -> v.region().startLine())
                    .thenComparing(Violation::ruleId));

            var coverageEngine = new io.github.rohithv16.aem.analyser.core.discovery.CoverageDiscoveryEngine();
            var coverageReport = coverageEngine.discover(context.projectRoot(), discoveryResult.modules());

            long durationMillis = (System.nanoTime() - start) / 1_000_000;
            return new AnalysisReport(context.projectRoot(), scanStatistics(discoveryResult, buildResult, htlUsages.size(), cqComponents.size()),
                    violations, durationMillis, coverageReport, codeMetrics);
        } catch (IOException | RuntimeException e) {
            throw new AnalysisException("Analysis failed: " + e.getMessage(), e);
        }
    }

    private List<JavaSourceParseResult> parseJavaSourcesParallel(AnalysisContext context,
                                                                 List<io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor> modules)
            throws IOException {
        List<Path> javaFiles = new ArrayList<>();
        for (ModuleDescriptor module : modules) {
            Path sourceRoot = module.moduleDirectory().resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(sourceRoot)) {
                stream.filter(f -> f.toString().endsWith(".java")).forEach(javaFiles::add);
            }
        }
        javaFiles.sort(Comparator.naturalOrder());

        JavaSourceParser parser = new JavaSourceParser();
        List<JavaSourceParseResult> results = new ArrayList<>(javaFiles.size());
        ExecutorService workers = Executors.newFixedThreadPool(context.workerThreads());
        try {
            List<Future<JavaSourceParseResult>> futures = new ArrayList<>(javaFiles.size());
            for (Path javaFile : javaFiles) {
                futures.add(workers.submit(() -> parser.parse(javaFile)));
            }
            for (Future<JavaSourceParseResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Java parsing interrupted", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    // unparseable file: skip
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
                                                        List<io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor> modules) {
        OsgiConfigScanner scanner = new OsgiConfigScanner();
        OsgiConfigParser parser = new OsgiConfigParser();
        List<OsgiConfigDefinition> configs = new ArrayList<>();
        for (ModuleDescriptor module : modules) {
            if (module.osgiConfigPaths().isEmpty()) {
                continue;
            }
            for (Path configPath : module.osgiConfigPaths()) {
                Set<String> runmodes = scanner.resolveRunmodes(
                        configPath.getParent().getFileName().toString());
                try {
                    configs.add(parser.parse(configPath, runmodes));
                } catch (IOException | RuntimeException e) {
                    // unparseable config: skip
                }
            }
        }
        return configs;
    }

    private DependencyBytecodeIndexer.DependencyIndex indexDependencies(AnalysisContext context) {
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
                    // unreadable root: skip
                }
            }
        }
        return indexer.index(jars);
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
