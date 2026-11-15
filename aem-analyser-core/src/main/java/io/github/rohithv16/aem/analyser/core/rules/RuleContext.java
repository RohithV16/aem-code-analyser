package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphIndex;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
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
                          Path projectRoot) {

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

    public ReferenceGraphIndex index() {
        return graph.getIndex();
    }

    public boolean isExternalPid(String pid) {
        return dependencyIndex != null && dependencyIndex.getComponentPids().contains(pid);
    }
}
