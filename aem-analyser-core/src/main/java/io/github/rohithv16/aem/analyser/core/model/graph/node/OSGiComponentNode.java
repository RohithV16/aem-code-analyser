package io.github.rohithv16.aem.analyser.core.model.graph.node;

import io.github.rohithv16.aem.analyser.core.model.graph.NodeType;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An OSGi Declarative Services component discovered via {@code @Component}.
 */
public final class OSGiComponentNode extends JavaClassNode {

    public enum ConfigurationPolicy { OPTIONAL, REQUIRE, IGNORE }

    public record DeclaredField(String name, String typeName, SourceLocation location, boolean isStatic) {}

    /** Declarative Services reference cardinality, as declared by {@code @Reference}. */
    public enum ReferenceCardinality {
        /** One service, and the component does not activate without it (the DS default). */
        MANDATORY,
        /** Zero or one service; activation does not depend on a provider. */
        OPTIONAL,
        /** All matching services; zero is a valid activation state. */
        MULTIPLE,
        /** At least one matching service; still not the single mandatory injection. */
        AT_LEAST_ONE;

        /** True when a missing provider blocks activation. */
        public boolean requiresProvider() {
            return this == MANDATORY;
        }
    }

    /**
     * One {@code @Reference} as declared in source: its target type, cardinality and location.
     *
     * <p>{@link #targetType()} is the resolved fully-qualified service type; {@link #name()} is the
     * field, bind method or constructor parameter it is declared on. A rule that asks whether the
     * reference can ever be satisfied needs the cardinality, which the name-to-type
     * {@link #getReferences()} map does not carry.</p>
     */
    public record ServiceReference(String name, String targetType,
                                   ReferenceCardinality cardinality, SourceLocation location) {
    }

    private final String componentClassName;
    private final String name;
    private final List<String> configurationPids;
    private final ConfigurationPolicy configurationPolicy;
    private final boolean immediate;
    private final boolean immediateExplicitlySet;
    private final Set<String> serviceInterfaces;
    private final Map<String, String> references;
    private final String designatedOcdFqn;
    private final Set<String> componentProperties;
    private final List<DeclaredField> declaredFields;
    private final List<ServiceReference> referenceDetails;
    private final String factoryId;
    private final boolean enabled;
    private final boolean programmaticActivation;

    public OSGiComponentNode(String fqn,
                             String name,
                             Kind kind,
                             Set<ModifierKind> modifiers,
                             String superClass,
                             Set<String> interfaces,
                             Path filePath,
                             SourceLocation sourceLocation,
                             List<String> configurationPids,
                             ConfigurationPolicy configurationPolicy,
                             boolean immediate,
                             boolean immediateExplicitlySet,
                             Set<String> serviceInterfaces,
                             Map<String, String> references,
                             String designatedOcdFqn,
                             Set<String> componentProperties) {
        this(fqn, name, kind, modifiers, superClass, interfaces, filePath, sourceLocation,
                configurationPids, configurationPolicy, immediate, immediateExplicitlySet,
                serviceInterfaces, references, designatedOcdFqn, componentProperties, List.of());
    }

    public OSGiComponentNode(String fqn,
                             String name,
                             Kind kind,
                             Set<ModifierKind> modifiers,
                             String superClass,
                             Set<String> interfaces,
                             Path filePath,
                             SourceLocation sourceLocation,
                             List<String> configurationPids,
                             ConfigurationPolicy configurationPolicy,
                             boolean immediate,
                             boolean immediateExplicitlySet,
                             Set<String> serviceInterfaces,
                             Map<String, String> references,
                             String designatedOcdFqn,
                             Set<String> componentProperties,
                             List<DeclaredField> declaredFields) {
        this(fqn, name, kind, modifiers, superClass, interfaces, filePath, sourceLocation,
                configurationPids, configurationPolicy, immediate, immediateExplicitlySet,
                serviceInterfaces, references, designatedOcdFqn, componentProperties, declaredFields,
                null, true, false);
    }

    public OSGiComponentNode(String fqn,
                             String name,
                             Kind kind,
                             Set<ModifierKind> modifiers,
                             String superClass,
                             Set<String> interfaces,
                             Path filePath,
                             SourceLocation sourceLocation,
                             List<String> configurationPids,
                             ConfigurationPolicy configurationPolicy,
                             boolean immediate,
                             boolean immediateExplicitlySet,
                             Set<String> serviceInterfaces,
                             Map<String, String> references,
                             String designatedOcdFqn,
                             Set<String> componentProperties,
                             List<DeclaredField> declaredFields,
                             String factoryId,
                             boolean enabled,
                             boolean programmaticActivation) {
        this(fqn, name, kind, modifiers, superClass, interfaces, filePath, sourceLocation,
                configurationPids, configurationPolicy, immediate, immediateExplicitlySet,
                serviceInterfaces, references, designatedOcdFqn, componentProperties, declaredFields,
                factoryId, enabled, programmaticActivation, List.of());
    }

    public OSGiComponentNode(String fqn,
                             String name,
                             Kind kind,
                             Set<ModifierKind> modifiers,
                             String superClass,
                             Set<String> interfaces,
                             Path filePath,
                             SourceLocation sourceLocation,
                             List<String> configurationPids,
                             ConfigurationPolicy configurationPolicy,
                             boolean immediate,
                             boolean immediateExplicitlySet,
                             Set<String> serviceInterfaces,
                             Map<String, String> references,
                             String designatedOcdFqn,
                             Set<String> componentProperties,
                             List<DeclaredField> declaredFields,
                             String factoryId,
                             boolean enabled,
                             boolean programmaticActivation,
                             List<ServiceReference> referenceDetails) {
        super(fqn, kind, modifiers, superClass, interfaces, filePath, sourceLocation);
        this.componentClassName = fqn;
        this.name = name;
        this.configurationPids = List.copyOf(configurationPids);
        this.configurationPolicy = configurationPolicy;
        this.immediate = immediate;
        this.immediateExplicitlySet = immediateExplicitlySet;
        this.serviceInterfaces = new LinkedHashSet<>(serviceInterfaces);
        this.references = new LinkedHashMap<>(references);
        this.designatedOcdFqn = designatedOcdFqn;
        this.componentProperties = new LinkedHashSet<>(componentProperties);
        this.declaredFields = List.copyOf(declaredFields == null ? List.of() : declaredFields);
        this.referenceDetails = List.copyOf(referenceDetails == null ? List.of() : referenceDetails);
        this.factoryId = factoryId;
        this.enabled = enabled;
        this.programmaticActivation = programmaticActivation;
    }

    @Override
    public NodeType getNodeType() {
        return NodeType.OSGI_COMPONENT;
    }

    public String getComponentClassFqn() {
        return componentClassName;
    }

    public Optional<String> getName() {
        return Optional.ofNullable(name);
    }

    public List<String> getConfigurationPids() {
        return configurationPids;
    }

    public ConfigurationPolicy getConfigurationPolicy() {
        return configurationPolicy;
    }

    public boolean isImmediate() {
        return immediate;
    }

    public boolean isImmediateExplicitlySet() {
        return immediateExplicitlySet;
    }

    public Set<String> getServiceInterfaces() {
        return serviceInterfaces;
    }

    public Map<String, String> getReferences() {
        return references;
    }

    public Optional<String> getDesignatedOcdFqn() {
        return Optional.ofNullable(designatedOcdFqn);
    }

    public Set<String> getComponentProperties() {
        return componentProperties;
    }

    public List<DeclaredField> getDeclaredFields() {
        return declaredFields;
    }

    /**
     * Every {@code @Reference} with its target type, cardinality and source location. Empty only for
     * nodes built by older callers/tests that populated {@link #getReferences()} directly.
     */
    public List<ServiceReference> getReferenceDetails() {
        return referenceDetails;
    }

    public Optional<String> getFactoryId() {
        return Optional.ofNullable(factoryId);
    }

    public boolean isFactoryComponent() {
        return factoryId != null && !factoryId.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isProgrammaticActivation() {
        return programmaticActivation;
    }
}
