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
    private final boolean nested;
    private final Set<String> referencedTypes;
    private final boolean exported;

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
        this(fqn, modifiers, superClass, interfaces, filePath, sourceLocation, adaptables, adapters,
                resourceType, defaultInjectionStrategy, injectedServices, false, Set.of(), false);
    }

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
                          Set<String> injectedServices,
                          boolean nested) {
        this(fqn, modifiers, superClass, interfaces, filePath, sourceLocation, adaptables, adapters,
                resourceType, defaultInjectionStrategy, injectedServices, nested, Set.of(), false);
    }

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
                          Set<String> injectedServices,
                          boolean nested,
                          Set<String> referencedTypes) {
        this(fqn, modifiers, superClass, interfaces, filePath, sourceLocation, adaptables, adapters,
                resourceType, defaultInjectionStrategy, injectedServices, nested, referencedTypes, false);
    }

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
                          Set<String> injectedServices,
                          boolean nested,
                          Set<String> referencedTypes,
                          boolean exported) {
        super(fqn, Kind.CLASS, modifiers, superClass, interfaces, filePath, sourceLocation);
        this.adaptables = new LinkedHashSet<>(adaptables);
        this.adapters = new LinkedHashSet<>(adapters);
        this.resourceType = resourceType;
        this.defaultInjectionStrategy = defaultInjectionStrategy;
        this.injectedServices = new LinkedHashSet<>(injectedServices);
        this.nested = nested;
        this.referencedTypes = new LinkedHashSet<>(referencedTypes);
        this.exported = exported;
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

    /**
     * Nested models are normally injection DTOs consumed by their enclosing model (for example
     * {@code @ChildResource List<ItemConfig>}), not standalone adaptables.
     */
    public boolean isNested() {
        return nested;
    }

    /**
     * Fully-qualified types referenced by this model (fields, constructor/method parameters and
     * return types, superclass, interfaces). Used to recognise models consumed through Sling Models
     * injection rather than {@code adaptTo()}.
     */
    public Set<String> getReferencedTypes() {
        return referencedTypes;
    }

    /** {@code @Exporter} models are consumed by Sling's exporter framework, not by adaptTo(). */
    public boolean isExported() {
        return exported;
    }
}
