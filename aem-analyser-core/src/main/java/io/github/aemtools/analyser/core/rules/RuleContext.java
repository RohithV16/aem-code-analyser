package io.github.aemtools.analyser.core.rules;

import io.github.aemtools.analyser.core.graph.ReferenceGraph;
import io.github.aemtools.analyser.core.graph.ReferenceGraphBuilder;
import io.github.aemtools.analyser.core.graph.ReferenceGraphIndex;
import io.github.aemtools.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.aemtools.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.aemtools.analyser.core.parser.bytecode.DependencyBytecodeIndexer;

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

    public ReferenceGraphIndex index() {
        return graph.getIndex();
    }

    public boolean isExternalPid(String pid) {
        return dependencyIndex != null && dependencyIndex.getComponentPids().contains(pid);
    }
}
