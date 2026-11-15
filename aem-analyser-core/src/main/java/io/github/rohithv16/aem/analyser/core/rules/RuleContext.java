package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphIndex;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.htl.CqComponentDefinition;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlUsage;
import io.github.rohithv16.aem.analyser.core.parser.bytecode.DependencyBytecodeIndexer;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

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
                          List<CqComponentDefinition> cqComponents) {

    public RuleContext(ReferenceGraph graph,
                       ReferenceGraphBuilder.GraphBuildResult buildResult,
                       Set<String> exportedPackages,
                       DependencyBytecodeIndexer.DependencyIndex dependencyIndex,
                       Set<String> ignoredServices,
                       Path projectRoot) {
        this(graph, buildResult, exportedPackages, dependencyIndex, ignoredServices, projectRoot, List.of(), List.of());
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

    public List<io.github.rohithv16.aem.analyser.core.parser.java.AdministrativeCall> administrativeCalls() {
        return buildResult.administrativeCalls() == null ? List.of() : buildResult.administrativeCalls();
    }

    public List<io.github.rohithv16.aem.analyser.core.model.perf.ResourceLeakFinding> resourceLeaks() {
        return buildResult.resourceLeaks() == null ? List.of() : buildResult.resourceLeaks();
    }

    public List<io.github.rohithv16.aem.analyser.core.model.perf.QueryTraversalFinding> queryTraversals() {
        return buildResult.queryTraversals() == null ? List.of() : buildResult.queryTraversals();
    }

    public ReferenceGraphIndex index() {
        return graph.getIndex();
    }

    public boolean isExternalPid(String pid) {
        return dependencyIndex != null && dependencyIndex.getComponentPids().contains(pid);
    }
}
