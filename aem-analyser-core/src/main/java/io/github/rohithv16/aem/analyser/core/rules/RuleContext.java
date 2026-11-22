package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphIndex;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.htl.CqComponentDefinition;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlUnsafeContextFinding;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlUsage;
import io.github.rohithv16.aem.analyser.core.parser.bytecode.DependencyBytecodeIndexer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared evaluation context handed to every rule evaluator.
 */
public record RuleContext(ReferenceGraph graph,
                          ReferenceGraphBuilder.GraphBuildResult buildResult,
                          Set<String> exportedPackages,
                          DependencyBytecodeIndexer.DependencyIndex dependencyIndex,
                          Set<String> ignoredServices,
                          Path projectRoot,
                          List<HtlUsage> htlUsages,
                          List<CqComponentDefinition> cqComponents,
                          List<HtlUnsafeContextFinding> htlUnsafeContexts,
                          List<io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor> modules,
                          List<io.github.rohithv16.aem.analyser.core.model.htl.HtlIncludeRef> htlIncludes,
                          List<io.github.rohithv16.aem.analyser.core.model.htl.HtlResourceRef> htlResources) {

    /** Compatibility constructor: no template-composition references were scanned. */
    public RuleContext(ReferenceGraph graph,
                       ReferenceGraphBuilder.GraphBuildResult buildResult,
                       Set<String> exportedPackages,
                       DependencyBytecodeIndexer.DependencyIndex dependencyIndex,
                       Set<String> ignoredServices,
                       Path projectRoot,
                       List<HtlUsage> htlUsages,
                       List<CqComponentDefinition> cqComponents,
                       List<HtlUnsafeContextFinding> htlUnsafeContexts,
                       List<io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor> modules) {
        this(graph, buildResult, exportedPackages, dependencyIndex, ignoredServices, projectRoot,
                htlUsages, cqComponents, htlUnsafeContexts, modules, List.of(), List.of());
    }

    public RuleContext {
        modules = List.copyOf(modules == null ? List.of() : modules);
        htlIncludes = List.copyOf(htlIncludes == null ? List.of() : htlIncludes);
        htlResources = List.copyOf(htlResources == null ? List.of() : htlResources);
        // Rules relativize violation paths against this root. A rooted-but-driveless path such as
        // "/mock/project" (or any relative root) makes Path.relativize throw
        // "'other' is different type of Path" on Windows, so the root is always absolutised here.
        projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    public RuleContext(ReferenceGraph graph,
                       ReferenceGraphBuilder.GraphBuildResult buildResult,
                       Set<String> exportedPackages,
                       DependencyBytecodeIndexer.DependencyIndex dependencyIndex,
                       Set<String> ignoredServices,
                       Path projectRoot,
                       List<HtlUsage> htlUsages,
                       List<CqComponentDefinition> cqComponents,
                       List<HtlUnsafeContextFinding> htlUnsafeContexts) {
        this(graph, buildResult, exportedPackages, dependencyIndex, ignoredServices, projectRoot,
                htlUsages, cqComponents, htlUnsafeContexts, List.of());
    }

    public RuleContext(ReferenceGraph graph,
                       ReferenceGraphBuilder.GraphBuildResult buildResult,
                       Set<String> exportedPackages,
                       DependencyBytecodeIndexer.DependencyIndex dependencyIndex,
                       Set<String> ignoredServices,
                       Path projectRoot,
                       List<HtlUsage> htlUsages,
                       List<CqComponentDefinition> cqComponents) {
        this(graph, buildResult, exportedPackages, dependencyIndex, ignoredServices, projectRoot, htlUsages, cqComponents, List.of(), List.of());
    }

    public RuleContext(ReferenceGraph graph,
                       ReferenceGraphBuilder.GraphBuildResult buildResult,
                       Set<String> exportedPackages,
                       DependencyBytecodeIndexer.DependencyIndex dependencyIndex,
                       Set<String> ignoredServices,
                       Path projectRoot) {
        this(graph, buildResult, exportedPackages, dependencyIndex, ignoredServices, projectRoot,
                List.of(), List.of(), List.of(), List.of());
    }

    public java.util.Optional<String> bundleSymbolicNameFor(Path file) {
        if (file == null || modules == null) {
            return java.util.Optional.empty();
        }
        Path absolute = file.toAbsolutePath().normalize();
        return modules.stream()
                .filter(module -> module.bundleSymbolicName() != null)
                .filter(module -> absolute.startsWith(module.moduleDirectory()))
                .max(java.util.Comparator.comparingInt(module -> module.moduleDirectory().getNameCount()))
                .map(io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor::bundleSymbolicName);
    }

    public List<OSGiComponentNode> components() {
        return List.copyOf(buildResult.componentsByFqn().values());
    }

    public List<OSGiConfigNode> configs() {
        return List.copyOf(buildResult.configNodes().values());
    }

    public List<io.github.rohithv16.aem.analyser.core.model.graph.node.SlingModelNode> slingModels() {
        return List.copyOf(buildResult.modelNodes().values());
    }

    public List<io.github.rohithv16.aem.analyser.core.parser.java.CodeBlockSnapshot> codeBlocks() {
        return buildResult.codeBlocks() == null ? List.of() : buildResult.codeBlocks();
    }

    public List<io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode> javaClasses() {
        return buildResult.classNodes() == null ? List.of() : List.copyOf(buildResult.classNodes().values());
    }

    /**
     * Every Java source file that declares a type, regardless of the annotation bucket it landed in.
     *
     * <p>{@link #javaClasses()} only contains types that are neither an OCD, a DS component nor a
     * Sling Model, because the parser files each type into exactly one bucket. File-scanning rules
     * (AEM-SEC-002, AEM-PERF-004, AEM-CLOUD-004) must use this method instead - iterating
     * {@code classNodes()} made them blind to every {@code @Component}, servlet and {@code @Model}.
     */
    public List<Path> javaTypeFiles() {
        java.util.Set<Path> files = new java.util.LinkedHashSet<>();
        addFile(files, buildResult.componentsByFqn());
        addFile(files, buildResult.modelNodes());
        addFile(files, buildResult.classNodes());
        // Metatype interfaces carry default methods and constants too.
        addFile(files, graph.getIndex().getOcdLookup());
        return List.copyOf(files);
    }

    private static <T extends io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode> void addFile(
            java.util.Set<Path> files, Map<String, T> nodes) {
        if (nodes == null) {
            return;
        }
        for (T node : nodes.values()) {
            if (node.getFilePath() != null) {
                files.add(node.getFilePath().toAbsolutePath().normalize());
            }
        }
    }

    public List<io.github.rohithv16.aem.analyser.core.parser.java.AdministrativeCall> administrativeCalls() {
        return buildResult.administrativeCalls() == null ? List.of() : buildResult.administrativeCalls();
    }

    public List<io.github.rohithv16.aem.analyser.core.model.perf.ResourceLeakFinding> resourceLeaks() {
        return buildResult.resourceLeaks() == null ? List.of() : buildResult.resourceLeaks();
    }

    public List<io.github.rohithv16.aem.analyser.core.model.perf.QueryTraversalFinding> queryTraversals() {
        return buildResult.queryTraversals() == null ? List.of() : buildResult.queryTraversals();
    }

    public List<io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall> subserviceCalls() {
        return buildResult.subserviceCalls() == null ? List.of() : buildResult.subserviceCalls();
    }

    public ReferenceGraphIndex index() {
        return graph.getIndex();
    }

    /**
     * Renders a violation path relative to the project root with forward slashes, so every rule and
     * every exporter agree on the same string on every platform.
     */
    public String relativePath(java.nio.file.Path file) {
        if (file == null) {
            return "";
        }
        java.nio.file.Path absolute = file.toAbsolutePath().normalize();
        java.nio.file.Path root = projectRoot;
        if (root != null) {
            try {
                return root.relativize(absolute).toString().replace('\\', '/');
            } catch (IllegalArgumentException ignored) {
                // different filesystem provider (or a path outside the root): fall through
            }
        }
        return absolute.toString().replace('\\', '/');
    }

    public boolean isExternalPid(String pid) {
        return dependencyIndex != null && dependencyIndex.getComponentPids().contains(pid);
    }
}
