package io.github.rohithv16.aem.analyser.core.parser.cache;

import io.github.rohithv16.aem.analyser.core.discovery.HtlScanner;
import io.github.rohithv16.aem.analyser.core.model.graph.ConfigFormat;
import io.github.rohithv16.aem.analyser.core.model.graph.DataType;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OcdPropertyMethodNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiOcdNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.SlingModelNode;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlIncludeRef;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlResourceRef;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlUnsafeContextFinding;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlUsage;
import io.github.rohithv16.aem.analyser.core.model.perf.QueryTraversalFinding;
import io.github.rohithv16.aem.analyser.core.model.perf.ResourceLeakFinding;
import io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall;
import io.github.rohithv16.aem.analyser.core.parser.config.ConfigPropertyEntry;
import io.github.rohithv16.aem.analyser.core.parser.config.OsgiConfigDefinition;
import io.github.rohithv16.aem.analyser.core.parser.java.AdministrativeCall;
import io.github.rohithv16.aem.analyser.core.parser.java.CodeBlockSnapshot;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParseResult;
import io.github.rohithv16.aem.analyser.core.parser.java.PendingAdaptTo;
import io.github.rohithv16.aem.analyser.core.parser.java.PendingConsumption;
import io.github.rohithv16.aem.analyser.core.parser.java.PendingServiceInjection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JSON shape of the parse cache, and the mapping between it and the domain records.
 *
 * <p>The domain objects are constructed by the parsers and never annotated for JSON: they mix
 * abstract node hierarchies, {@link Path} fields and {@link java.util.Optional} accessors. The
 * cache therefore stores narrow DTO records with plain strings and rebuilds the exact same
 * objects through their public constructors, so a cache hit is indistinguishable from a parse.</p>
 */
final class ParseCacheCodec {

    private ParseCacheCodec() {
    }

    // ── Cache envelope ──────────────────────────────────────────────────────────

    record Entry(int schema, String version, String parserFingerprint, long maxFileSizeBytes,
                 String filePath, String kind, com.fasterxml.jackson.databind.JsonNode data) {
    }

    record JavaParseDto(String packageName,
                        int lineCount,
                        List<JavaClassDto> javaClasses,
                        List<ComponentDto> components,
                        List<OcdDto> ocds,
                        List<SlingModelDto> slingModels,
                        List<PendingConsumptionDto> pendingConsumptions,
                        List<PendingServiceInjectionDto> pendingServiceInjections,
                        List<PendingAdaptToDto> pendingAdaptTo,
                        List<String> exportedPackages,
                        List<CodeBlockDto> codeBlocks,
                        List<AdministrativeCallDto> administrativeCalls,
                        List<ResourceLeakDto> resourceLeaks,
                        List<QueryTraversalDto> queryTraversals,
                        List<SubserviceCallDto> subserviceCalls) {
    }

    record LocationDto(String path, int line, int column) {
    }

    record JavaClassDto(String fqn, String kind, List<String> modifiers, String superClass,
                        List<String> interfaces, boolean exported, LocationDto location) {
    }

    record ComponentDto(JavaClassDto base, String name, List<String> configurationPids,
                        String configurationPolicy, boolean immediate, boolean immediateExplicitlySet,
                        List<String> serviceInterfaces, Map<String, String> references,
                        String designatedOcdFqn, List<String> componentProperties,
                        List<DeclaredFieldDto> declaredFields, List<ServiceReferenceDto> referenceDetails,
                        String factoryId, boolean enabled, boolean programmaticActivation) {
    }

    record DeclaredFieldDto(String name, String typeName, LocationDto location, boolean isStatic) {
    }

    record ServiceReferenceDto(String name, String targetType, String cardinality, LocationDto location) {
    }

    record OcdDto(JavaClassDto base, String pid, String metatypeName, List<OcdMethodDto> propertyMethods) {
    }

    record OcdMethodDto(String methodName, String explicitAttributeId, String normalizedOsgiPropName,
                        String returnType, List<String> defaultValue, boolean required, LocationDto location) {
    }

    record SlingModelDto(JavaClassDto base, List<String> adaptables, List<String> adapters,
                         String resourceType, String defaultInjectionStrategy,
                         List<String> injectedServices, boolean nested, List<String> referencedTypes,
                         boolean exported, List<InjectedFieldDto> injectedFields) {
    }

    record InjectedFieldDto(String name, String typeName, boolean optional,
                            List<String> annotations, String viaValue, String viaType,
                            LocationDto location) {
    }

    record PendingConsumptionDto(String componentFqn, String ocdTypeName, String methodName,
                                 LocationDto callSite, String invocationTypeName,
                                 String enclosingMethodName) {
    }

    record PendingServiceInjectionDto(String consumerFqn, String serviceTypeName,
                                      boolean consumerIsSlingModel) {
    }

    record PendingAdaptToDto(String sourceClassFqn, String targetTypeName) {
    }

    record CodeBlockDto(String classFqn, String methodName, int startLine, int endLine, int lineCount,
                        int statementCount, String normalizedHash, String preview) {
    }

    record AdministrativeCallDto(String methodName, String enclosingClassFqn, LocationDto location) {
    }

    record ResourceLeakDto(int line, int column, String variableName, String callName, String kind) {
    }

    record QueryTraversalDto(int line, int column, String queryString, String queryLanguage) {
    }

    record SubserviceCallDto(String subserviceName, String callingClassFqn, int line, int column,
                             String resolution) {
    }

    record HtlDto(List<UsageDto> usages, List<UnsafeDto> unsafeContexts,
                  List<IncludeDto> includes, List<ResourceDto> resources) {
    }

    record UsageDto(int line, int column, String identifier, String target) {
    }

    record UnsafeDto(int line, int column, String snippet) {
    }

    record IncludeDto(int line, int column, String target) {
    }

    record ResourceDto(int line, int column, String resourceType) {
    }

    record ConfigDto(String pid, String factoryIdentifier, boolean isFactory, boolean factoryAmbiguous,
                     List<String> runmodes, String format, List<ConfigPropertyDto> properties) {
    }

    record ConfigPropertyDto(String baseKey, String rawKey, String typeHint, String rawValue,
                             String inferredType, boolean isEnvPlaceholder, int line, int column) {
    }

    // ── Java parse result ───────────────────────────────────────────────────────

    static JavaParseDto toDto(JavaSourceParseResult result) {
        return new JavaParseDto(
                result.packageName(),
                result.lineCount(),
                result.javaClasses().stream().map(ParseCacheCodec::classDto).toList(),
                result.components().stream().map(ParseCacheCodec::componentDto).toList(),
                result.ocds().stream().map(ParseCacheCodec::ocdDto).toList(),
                result.slingModels().stream().map(ParseCacheCodec::slingModelDto).toList(),
                result.pendingConsumptions().stream().map(ParseCacheCodec::consumptionDto).toList(),
                result.pendingServiceInjections().stream()
                        .map(p -> new PendingServiceInjectionDto(
                                p.consumerFqn(), p.serviceTypeName(), p.consumerIsSlingModel()))
                        .toList(),
                result.pendingAdaptTo().stream()
                        .map(p -> new PendingAdaptToDto(p.sourceClassFqn(), p.targetTypeName()))
                        .toList(),
                List.copyOf(result.exportedPackages()),
                result.codeBlocks().stream()
                        .map(c -> new CodeBlockDto(c.classFqn(), c.methodName(), c.startLine(), c.endLine(),
                                c.lineCount(), c.statementCount(), c.normalizedHash(), c.preview()))
                        .toList(),
                result.administrativeCalls().stream()
                        .map(a -> new AdministrativeCallDto(a.methodName(), a.enclosingClassFqn(),
                                locationDto(a.location())))
                        .toList(),
                result.resourceLeaks().stream()
                        .map(r -> new ResourceLeakDto(r.line(), r.column(), r.variableName(), r.callName(),
                                r.kind() == null ? null : r.kind().name()))
                        .toList(),
                result.queryTraversals().stream()
                        .map(q -> new QueryTraversalDto(q.line(), q.column(), q.queryString(),
                                q.queryLanguage()))
                        .toList(),
                result.subserviceCalls().stream()
                        .map(s -> new SubserviceCallDto(s.subserviceName(), s.callingClassFqn(), s.line(),
                                s.column(), s.resolution() == null ? null : s.resolution().name()))
                        .toList());
    }

    static JavaSourceParseResult fromDto(JavaParseDto dto, Path filePath) {
        List<JavaClassNode> classes = new ArrayList<>();
        for (JavaClassDto c : dto.javaClasses()) {
            classes.add(classNode(c, filePath));
        }
        List<OSGiComponentNode> components = new ArrayList<>();
        for (ComponentDto c : dto.components()) {
            components.add(componentNode(c, filePath));
        }
        List<OSGiOcdNode> ocds = new ArrayList<>();
        for (OcdDto o : dto.ocds()) {
            ocds.add(ocdNode(o, filePath));
        }
        List<SlingModelNode> models = new ArrayList<>();
        for (SlingModelDto m : dto.slingModels()) {
            models.add(slingModelNode(m, filePath));
        }
        List<PendingConsumption> consumptions = new ArrayList<>();
        for (PendingConsumptionDto p : dto.pendingConsumptions()) {
            consumptions.add(new PendingConsumption(p.componentFqn(), p.ocdTypeName(), p.methodName(),
                    filePath, location(p.callSite()),
                    java.util.Optional.ofNullable(p.invocationTypeName()), p.enclosingMethodName()));
        }
        Set<String> exported = new LinkedHashSet<>(dto.exportedPackages());
        List<CodeBlockSnapshot> codeBlocks = new ArrayList<>();
        for (CodeBlockDto c : dto.codeBlocks()) {
            codeBlocks.add(new CodeBlockSnapshot(filePath, c.classFqn(), c.methodName(), c.startLine(),
                    c.endLine(), c.lineCount(), c.statementCount(), c.normalizedHash(), c.preview()));
        }
        List<AdministrativeCall> administrativeCalls = new ArrayList<>();
        for (AdministrativeCallDto a : dto.administrativeCalls()) {
            administrativeCalls.add(new AdministrativeCall(a.methodName(), a.enclosingClassFqn(),
                    filePath, location(a.location())));
        }
        List<ResourceLeakFinding> leaks = new ArrayList<>();
        for (ResourceLeakDto r : dto.resourceLeaks()) {
            leaks.add(new ResourceLeakFinding(filePath, r.line(), r.column(), r.variableName(),
                    r.callName(), r.kind() == null ? null
                    : ResourceLeakFinding.LeakKind.valueOf(r.kind())));
        }
        List<QueryTraversalFinding> queries = new ArrayList<>();
        for (QueryTraversalDto q : dto.queryTraversals()) {
            queries.add(new QueryTraversalFinding(filePath, q.line(), q.column(), q.queryString(),
                    q.queryLanguage()));
        }
        List<SubserviceCall> subserviceCalls = new ArrayList<>();
        for (SubserviceCallDto s : dto.subserviceCalls()) {
            subserviceCalls.add(new SubserviceCall(s.subserviceName(), s.callingClassFqn(), filePath,
                    s.line(), s.column(), s.resolution() == null ? null
                    : SubserviceCall.Resolution.valueOf(s.resolution())));
        }
        return new JavaSourceParseResult(filePath, dto.packageName(), classes, components, ocds, models,
                consumptions,
                dto.pendingServiceInjections().stream()
                        .map(p -> new PendingServiceInjection(p.consumerFqn(), p.serviceTypeName(),
                                p.consumerIsSlingModel(), filePath))
                        .toList(),
                dto.pendingAdaptTo().stream()
                        .map(p -> new PendingAdaptTo(p.sourceClassFqn(), p.targetTypeName(), filePath))
                        .toList(),
                exported, codeBlocks, administrativeCalls, leaks, queries, subserviceCalls,
                dto.lineCount());
    }

    private static JavaClassDto classDto(JavaClassNode node) {
        return new JavaClassDto(node.getFqn(), node.getKind().name(),
                node.getModifiers().stream().map(Enum::name).toList(),
                node.getSuperClass().orElse(null), List.copyOf(node.getInterfaces()),
                node.isExported(), locationDto(node.getSourceLocation()));
    }

    private static JavaClassNode classNode(JavaClassDto dto, Path filePath) {
        JavaClassNode node = new JavaClassNode(dto.fqn(), JavaClassNode.Kind.valueOf(dto.kind()),
                modifiers(dto.modifiers()), dto.superClass(), new LinkedHashSet<>(dto.interfaces()),
                filePath, location(dto.location()));
        node.setExported(dto.exported());
        return node;
    }

    private static ComponentDto componentDto(OSGiComponentNode node) {
        return new ComponentDto(classDto(node), node.getName().orElse(null),
                List.copyOf(node.getConfigurationPids()), name(node.getConfigurationPolicy()), node.isImmediate(),
                node.isImmediateExplicitlySet(), List.copyOf(node.getServiceInterfaces()),
                new LinkedHashMap<>(node.getReferences()), node.getDesignatedOcdFqn().orElse(null),
                List.copyOf(node.getComponentProperties()),
                node.getDeclaredFields().stream()
                        .map(f -> new DeclaredFieldDto(f.name(), f.typeName(), locationDto(f.location()),
                                f.isStatic()))
                        .toList(),
                node.getReferenceDetails().stream()
                        .map(r -> new ServiceReferenceDto(r.name(), r.targetType(), name(r.cardinality()),
                                locationDto(r.location())))
                        .toList(),
                node.getFactoryId().orElse(null), node.isEnabled(), node.isProgrammaticActivation());
    }

    private static OSGiComponentNode componentNode(ComponentDto dto, Path filePath) {
        JavaClassDto base = dto.base();
        List<OSGiComponentNode.DeclaredField> fields = dto.declaredFields().stream()
                .map(f -> new OSGiComponentNode.DeclaredField(f.name(), f.typeName(),
                        location(f.location()), f.isStatic()))
                .toList();
        List<OSGiComponentNode.ServiceReference> references = dto.referenceDetails().stream()
                .map(r -> new OSGiComponentNode.ServiceReference(r.name(), r.targetType(),
                        OSGiComponentNode.ReferenceCardinality.valueOf(r.cardinality()),
                        location(r.location())))
                .toList();
        return new OSGiComponentNode(base.fqn(), dto.name(),
                JavaClassNode.Kind.valueOf(base.kind()), modifiers(base.modifiers()), base.superClass(),
                new LinkedHashSet<>(base.interfaces()), filePath, location(base.location()),
                List.copyOf(dto.configurationPids()),
                OSGiComponentNode.ConfigurationPolicy.valueOf(dto.configurationPolicy()),
                dto.immediate(), dto.immediateExplicitlySet(), new LinkedHashSet<>(dto.serviceInterfaces()),
                new LinkedHashMap<>(dto.references()), dto.designatedOcdFqn(),
                new LinkedHashSet<>(dto.componentProperties()), fields, dto.factoryId(), dto.enabled(),
                dto.programmaticActivation(), references);
    }

    private static OcdDto ocdDto(OSGiOcdNode node) {
        List<OcdMethodDto> methods = node.getPropertyMethods().values().stream()
                .map(m -> new OcdMethodDto(m.getMethodName(), m.getExplicitAttributeId().orElse(null),
                        m.getNormalizedOsgiPropName(), m.getReturnType(), List.copyOf(m.getDefaultValue()),
                        m.isRequired(), locationDto(m.getSourceLocation())))
                .toList();
        return new OcdDto(classDto(node), node.getPid(), node.getMetatypeName().orElse(null), methods);
    }

    private static OSGiOcdNode ocdNode(OcdDto dto, Path filePath) {
        JavaClassDto base = dto.base();
        OSGiOcdNode node = new OSGiOcdNode(base.fqn(), dto.pid(), dto.metatypeName(),
                modifiers(base.modifiers()), filePath, location(base.location()));
        for (OcdMethodDto m : dto.propertyMethods()) {
            node.addPropertyMethod(new OcdPropertyMethodNode(base.fqn(), m.methodName(),
                    m.explicitAttributeId(), m.normalizedOsgiPropName(), m.returnType(),
                    List.copyOf(m.defaultValue()), m.required(), filePath, location(m.location())));
        }
        return node;
    }

    private static SlingModelDto slingModelDto(SlingModelNode node) {
        return new SlingModelDto(classDto(node), List.copyOf(node.getAdaptables()),
                List.copyOf(node.getAdapters()), node.getResourceType().orElse(null),
                name(node.getDefaultInjectionStrategy()), List.copyOf(node.getInjectedServices()),
                node.isNested(), List.copyOf(node.getReferencedTypes()), node.isExported(),
                node.getInjectedFields().stream()
                        .map(field -> new InjectedFieldDto(field.name(), field.typeName(),
                                field.optional(), List.copyOf(field.annotations()),
                                field.viaValue(), field.viaType(),
                                new LocationDto(null, field.line(), field.column())))
                        .toList());
    }

    private static SlingModelNode slingModelNode(SlingModelDto dto, Path filePath) {
        JavaClassDto base = dto.base();
        return new SlingModelNode(base.fqn(), modifiers(base.modifiers()), base.superClass(),
                new LinkedHashSet<>(base.interfaces()), filePath, location(base.location()),
                new LinkedHashSet<>(dto.adaptables()), new LinkedHashSet<>(dto.adapters()),
                dto.resourceType(),
                SlingModelNode.DefaultInjectionStrategy.valueOf(dto.defaultInjectionStrategy()),
                new LinkedHashSet<>(dto.injectedServices()), dto.nested(),
                new LinkedHashSet<>(dto.referencedTypes()), dto.exported(),
                dto.injectedFields() == null ? List.of() : dto.injectedFields().stream()
                        .map(field -> new SlingModelNode.InjectedField(field.name(), field.typeName(),
                                field.optional(), new LinkedHashSet<>(field.annotations()),
                                field.viaValue(), field.viaType(),
                                field.location() == null ? 1 : field.location().line(),
                                field.location() == null ? 1 : field.location().column()))
                        .toList());
    }

    private static PendingConsumptionDto consumptionDto(PendingConsumption p) {
        return new PendingConsumptionDto(p.componentFqn(), p.ocdTypeName(), p.methodName(),
                locationDto(p.callSite()), p.invocationTypeName().orElse(null),
                p.enclosingMethodName());
    }

    private static Set<JavaClassNode.ModifierKind> modifiers(List<String> names) {
        EnumSet<JavaClassNode.ModifierKind> set = EnumSet.noneOf(JavaClassNode.ModifierKind.class);
        if (names != null) {
            for (String name : names) {
                set.add(JavaClassNode.ModifierKind.valueOf(name));
            }
        }
        return set;
    }

    private static LocationDto locationDto(SourceLocation location) {
        if (location == null) {
            return null;
        }
        return new LocationDto(location.projectRelativePath() == null ? null
                : location.projectRelativePath().toString(), location.line(), location.column());
    }

    private static SourceLocation location(LocationDto dto) {
        if (dto == null) {
            return null;
        }
        return new SourceLocation(dto.path() == null ? null : Path.of(dto.path()), dto.line(), dto.column());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    // ── HTL ─────────────────────────────────────────────────────────────────────

    static HtlDto toDto(HtlScanner.HtlScanResult result) {
        return new HtlDto(
                result.usages().stream()
                        .map(u -> new UsageDto(u.line(), u.column(), u.identifier(), u.target()))
                        .toList(),
                result.unsafeContexts().stream()
                        .map(u -> new UnsafeDto(u.line(), u.column(), u.snippet()))
                        .toList(),
                result.includes().stream()
                        .map(i -> new IncludeDto(i.line(), i.column(), i.target()))
                        .toList(),
                result.resources().stream()
                        .map(r -> new ResourceDto(r.line(), r.column(), r.resourceType()))
                        .toList());
    }

    static HtlScanner.HtlScanResult fromDto(HtlDto dto, Path filePath) {
        List<HtlUsage> usages = new ArrayList<>();
        for (UsageDto u : dto.usages()) {
            usages.add(new HtlUsage(filePath, u.line(), u.column(), u.identifier(), u.target()));
        }
        List<HtlUnsafeContextFinding> unsafe = new ArrayList<>();
        for (UnsafeDto u : dto.unsafeContexts()) {
            unsafe.add(new HtlUnsafeContextFinding(filePath, u.line(), u.column(), u.snippet()));
        }
        List<HtlIncludeRef> includes = new ArrayList<>();
        for (IncludeDto i : dto.includes()) {
            includes.add(new HtlIncludeRef(filePath, i.line(), i.column(), i.target()));
        }
        List<HtlResourceRef> resources = new ArrayList<>();
        for (ResourceDto r : dto.resources()) {
            resources.add(new HtlResourceRef(filePath, r.line(), r.column(), r.resourceType()));
        }
        return new HtlScanner.HtlScanResult(usages, unsafe, includes, resources);
    }

    // ── OSGi configuration ──────────────────────────────────────────────────────

    static ConfigDto toDto(OsgiConfigDefinition definition) {
        List<ConfigPropertyDto> properties = new ArrayList<>();
        for (Map.Entry<String, ConfigPropertyEntry> entry : definition.properties().entrySet()) {
            ConfigPropertyEntry p = entry.getValue();
            properties.add(new ConfigPropertyDto(p.baseKey(), p.rawKey(), p.typeHint(), p.rawValue(),
                    p.inferredType() == null ? null : p.inferredType().name(), p.isEnvPlaceholder(),
                    p.line(), p.column()));
        }
        return new ConfigDto(definition.pid(), definition.factoryIdentifier(), definition.isFactory(),
                definition.factoryAmbiguous(), List.copyOf(definition.runmodes()),
                name(definition.format()), properties);
    }

    static OsgiConfigDefinition fromDto(ConfigDto dto, Path filePath) {
        Map<String, ConfigPropertyEntry> properties = new LinkedHashMap<>();
        for (ConfigPropertyDto p : dto.properties()) {
            DataType type = p.inferredType() == null ? null
                    : DataType.valueOf(p.inferredType().toUpperCase(Locale.ROOT));
            ConfigPropertyEntry entry = new ConfigPropertyEntry(p.baseKey(), p.rawKey(), p.typeHint(),
                    p.rawValue(), type, p.isEnvPlaceholder(), p.line(), p.column());
            properties.put(entry.baseKey(), entry);
        }
        return new OsgiConfigDefinition(filePath, dto.pid(), dto.factoryIdentifier(), dto.isFactory(),
                dto.factoryAmbiguous(), new LinkedHashSet<>(dto.runmodes()),
                dto.format() == null ? null : ConfigFormat.valueOf(dto.format()), properties);
    }
}
