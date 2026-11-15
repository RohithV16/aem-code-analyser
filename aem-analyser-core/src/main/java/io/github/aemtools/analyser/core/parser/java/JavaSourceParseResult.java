package io.github.aemtools.analyser.core.parser.java;

import io.github.aemtools.analyser.core.model.graph.node.JavaClassNode;
import io.github.aemtools.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.aemtools.analyser.core.model.graph.node.OSGiOcdNode;
import io.github.aemtools.analyser.core.model.graph.node.SlingModelNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Intermediate DTO emitted by one Java source file parse. Holds every extracted symbol
 * plus pending cross-boundary edges; the CompilationUnit AST is released immediately after.
 */
public record JavaSourceParseResult(
        Path filePath,
        String packageName,
        List<JavaClassNode> javaClasses,
        List<OSGiComponentNode> components,
        List<OSGiOcdNode> ocds,
        List<SlingModelNode> slingModels,
        List<PendingConsumption> pendingConsumptions,
        List<PendingServiceInjection> pendingServiceInjections,
        List<PendingAdaptTo> pendingAdaptTo,
        Set<String> exportedPackages) {

    public JavaSourceParseResult {
        javaClasses = List.copyOf(javaClasses == null ? List.of() : javaClasses);
        components = List.copyOf(components == null ? List.of() : components);
        ocds = List.copyOf(ocds == null ? List.of() : ocds);
        slingModels = List.copyOf(slingModels == null ? List.of() : slingModels);
        pendingConsumptions = List.copyOf(pendingConsumptions == null ? List.of() : pendingConsumptions);
        pendingServiceInjections = List.copyOf(pendingServiceInjections == null ? List.of() : pendingServiceInjections);
        pendingAdaptTo = List.copyOf(pendingAdaptTo == null ? List.of() : pendingAdaptTo);
        exportedPackages = Set.copyOf(exportedPackages == null ? Set.of() : exportedPackages);
    }
}
