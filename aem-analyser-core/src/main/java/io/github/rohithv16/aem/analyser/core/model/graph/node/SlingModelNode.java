package io.github.rohithv16.aem.analyser.core.model.graph.node;

import io.github.rohithv16.aem.analyser.core.model.graph.NodeType;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * A Sling Model class discovered via {@code @Model}.
 */
public final class SlingModelNode extends JavaClassNode {

    public enum DefaultInjectionStrategy { OPTIONAL, REQUIRED }

    private final Set<String> adaptables;
    private final Set<String> adapters;
    private final String resourceType;
    private final DefaultInjectionStrategy defaultInjectionStrategy;
    private final Set<String> injectedServices;

    public SlingModelNode(String fqn,
                          Set<ModifierKind> modifiers,
                          String superClass,
                          Set<String> interfaces,
                          Path filePath,
                          SourceLocation sourceLocation,
                          Set<String> adaptables,
                          Set<String> adapters,
                          String resourceType,
                          DefaultInjectionStrategy defaultInjectionStrategy,
                          Set<String> injectedServices) {
        super(fqn, Kind.CLASS, modifiers, superClass, interfaces, filePath, sourceLocation);
        this.adaptables = new LinkedHashSet<>(adaptables);
        this.adapters = new LinkedHashSet<>(adapters);
        this.resourceType = resourceType;
        this.defaultInjectionStrategy = defaultInjectionStrategy;
        this.injectedServices = new LinkedHashSet<>(injectedServices);
    }

    @Override
    public NodeType getNodeType() {
        return NodeType.SLING_MODEL;
    }

    public Set<String> getAdaptables() {
        return adaptables;
    }

    public Set<String> getAdapters() {
        return adapters;
    }

    public Optional<String> getResourceType() {
        return Optional.ofNullable(resourceType);
    }

    public DefaultInjectionStrategy getDefaultInjectionStrategy() {
        return defaultInjectionStrategy;
    }

    public Set<String> getInjectedServices() {
        return injectedServices;
    }
}
