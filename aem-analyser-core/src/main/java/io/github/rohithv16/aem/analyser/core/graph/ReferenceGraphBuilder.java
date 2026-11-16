package io.github.rohithv16.aem.analyser.core.graph;

import io.github.rohithv16.aem.analyser.core.discovery.ExportPackageCollector;
import io.github.rohithv16.aem.analyser.core.graph.MetatypeNameNormalizer;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleClassification;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor;
import io.github.rohithv16.aem.analyser.core.model.graph.EdgeType;
import io.github.rohithv16.aem.analyser.core.model.graph.PolyglotEdge;
import io.github.rohithv16.aem.analyser.core.model.graph.PolyglotNode;
import io.github.rohithv16.aem.analyser.core.model.graph.edge.ConsumesPropertyEdge;
import io.github.rohithv16.aem.analyser.core.model.graph.edge.SimpleEdge;
import io.github.rohithv16.aem.analyser.core.model.graph.node.ConfigPropertyNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiOcdNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OcdPropertyMethodNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.SlingModelNode;
import io.github.rohithv16.aem.analyser.core.parser.bytecode.DependencyBytecodeIndexer;
import io.github.rohithv16.aem.analyser.core.parser.config.OsgiConfigDefinition;
import io.github.rohithv16.aem.analyser.core.parser.config.OsgiConfigParser;
import io.github.rohithv16.aem.analyser.core.parser.java.PendingAdaptTo;
import io.github.rohithv16.aem.analyser.core.parser.java.PendingConsumption;
import io.github.rohithv16.aem.analyser.core.parser.java.PendingServiceInjection;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParseResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * FEAT-GRAPH-01/03: builds the cross-boundary reference graph using the two-phase
 * thread-confined ingestion pipeline:
 * <ol>
 *   <li>Phase A (parallel workers, upstream): parse files into intermediate DTOs</li>
 *   <li>Phase B (ingestion thread): register all vertices + secondary indices</li>
 *   <li>Phase C (edge resolution): resolve pending edges with O(1) index lookups</li>
 *   <li>Phase D: batch commit edges and seal the graph</li>
 * </ol>
 */
public final class ReferenceGraphBuilder {

    private final ReferenceGraph graph = new ReferenceGraph();

    public record GraphInput(List<ModuleDescriptor> modules,
                             List<JavaSourceParseResult> javaResults,
                             List<OsgiConfigDefinition> configs,
                             DependencyBytecodeIndexer.DependencyIndex dependencyIndex,
                             Path projectRoot) {
    }

    public GraphBuildResult build(GraphInput input) {
        // ── Phase B: vertex ingestion (thread-confined, no locks) ──────────────
        Map<String, OSGiComponentNode> componentsByFqn = new LinkedHashMap<>();
        Map<String, ConfigPropertyNode> configPropertyNodes = new LinkedHashMap<>();
        Map<String, OSGiConfigNode> configNodes = new LinkedHashMap<>();
        Map<String, JavaClassNode> classNodes = new LinkedHashMap<>();
        Map<String, SlingModelNode> modelNodes = new LinkedHashMap<>();

        for (JavaSourceParseResult java : input.javaResults()) {
            for (OSGiComponentNode component : java.components()) {
                if (!componentsByFqn.containsKey(component.getFqn())) {
                    componentsByFqn.put(component.getFqn(), component);
                    graph.addVertex(component);
                    graph.getIndex().registerComponent(component);
                }
            }
            for (OSGiOcdNode ocd : java.ocds()) {
                if (graph.getIndex().findOcdByFqn(ocd.getOcdFqn()) == null) {
                    graph.addVertex(ocd);
                    graph.getIndex().registerOcd(ocd);
                    for (OcdPropertyMethodNode method : ocd.getPropertyMethods().values()) {
                        graph.addVertex(method);
                    }
                }
            }
            for (SlingModelNode model : java.slingModels()) {
                if (!modelNodes.containsKey(model.getFqn())) {
                    modelNodes.put(model.getFqn(), model);
                    graph.addVertex(model);
                    graph.getIndex().registerSlingModel(model);
                }
            }
            for (JavaClassNode javaClass : java.javaClasses()) {
                if (!classNodes.containsKey(javaClass.getFqn())) {
                    classNodes.put(javaClass.getFqn(), javaClass);
                    graph.addVertex(javaClass);
                    graph.getIndex().registerJavaClass(javaClass);
                }
            }
        }

        for (OsgiConfigDefinition config : input.configs()) {
            OSGiConfigNode configNode = toConfigNode(config, configPropertyNodes, componentsByFqn);
            if (!configNodes.containsKey(configNode.getId())) {
                configNodes.put(configNode.getId(), configNode);
                graph.addVertex(configNode);
                graph.getIndex().registerConfig(configNode);
                for (ConfigPropertyNode property : configNode.getProperties().values()) {
                    configPropertyNodes.put(property.getId(), property);
                    graph.addVertex(property);
                }
            }
        }

        // Exported package marking (bundle Export-Package / @Export annotations)
        Set<String> exportedPatterns = new LinkedHashSet<>();
        for (ModuleDescriptor module : input.modules()) {
            exportedPatterns.addAll(module.exportedPackages());
        }

        // ── Phase C: edge resolution via O(1) index lookups ────────────────────
        List<PolyglotEdge> edges = new ArrayList<>();

        // CONTAINS_PROPERTY
        for (OSGiConfigNode configNode : configNodes.values()) {
            for (ConfigPropertyNode property : configNode.getProperties().values()) {
                edges.add(new SimpleEdge(configNode, property, EdgeType.CONTAINS_PROPERTY,
                        "Configuration " + configNode.getPid() + " contains property " + property.getKey()));
            }
        }

        // CONFIGURES: config PID -> component active PIDs
        Map<OSGiConfigNode, OSGiComponentNode> configuringComponent = new HashMap<>();
        for (OSGiConfigNode configNode : configNodes.values()) {
            if (configNode.getPid() == null) {
                continue;
            }
            List<OSGiComponentNode> candidates = resolveComponentForPid(configNode.getPid(), componentsByFqn);
            if (candidates.size() == 1) {
                OSGiComponentNode component = candidates.get(0);
                configuringComponent.put(configNode, component);
                edges.add(new SimpleEdge(configNode, component, EdgeType.CONFIGURES,
                        "Configuration " + configNode.getPid() + " configures component " + component.getId()));
            } else if (candidates.size() > 1) {
                OSGiComponentNode component = candidates.get(0);
                configuringComponent.put(configNode, component);
                for (OSGiComponentNode candidate : candidates) {
                    edges.add(new SimpleEdge(configNode, candidate, EdgeType.CONFIGURES,
                            "Configuration " + configNode.getPid() + " configures component " + candidate.getId()));
                }
            }
        }

        // DESIGNATES_OCD
        for (OSGiComponentNode component : componentsByFqn.values()) {
            component.getDesignatedOcdFqn().ifPresent(ocdFqn -> {
                OSGiOcdNode ocd = graph.getIndex().findOcdByFqn(ocdFqn);
                if (ocd != null) {
                    edges.add(new SimpleEdge(component, ocd, EdgeType.DESIGNATES_OCD,
                            component.getId() + " designates OCD " + ocd.getId()));
                }
            });
        }

        // DEFINES_PROPERTY
        for (OSGiOcdNode ocd : graph.getIndex().getOcdLookup().values()) {
            for (OcdPropertyMethodNode method : ocd.getPropertyMethods().values()) {
                edges.add(new SimpleEdge(ocd, method, EdgeType.DEFINES_PROPERTY,
                        ocd.getId() + " defines attribute " + method.getNormalizedOsgiPropName()));
            }
        }

        // BINDS_PROPERTY: config property -> matching OCD attribute method
        for (Map.Entry<OSGiConfigNode, OSGiComponentNode> entry : configuringComponent.entrySet()) {
            OSGiComponentNode component = entry.getValue();
            component.getDesignatedOcdFqn()
                    .map(fqn -> graph.getIndex().findOcdByFqn(fqn))
                    .ifPresent(ocd -> {
                        for (ConfigPropertyNode property : entry.getKey().getProperties().values()) {
                            OcdPropertyMethodNode match = findBoundMethod(ocd, property.getKey());
                            if (match != null) {
                                edges.add(new SimpleEdge(property, match, EdgeType.BINDS_PROPERTY,
                                        "Property " + property.getKey() + " binds attribute "
                                                + match.getNormalizedOsgiPropName()));
                            }
                        }
                    });
        }

        // CONSUMES_PROPERTY (pending edges from parallel parse phase)
        for (JavaSourceParseResult java : input.javaResults()) {
            for (PendingConsumption pending : java.pendingConsumptions()) {
                OSGiOcdNode ocd = graph.getIndex().findOcdByFqn(pending.ocdTypeName());
                if (ocd == null) {
                    continue;
                }
                OcdPropertyMethodNode method = ocd.getPropertyMethods().get(pending.methodName());
                if (method == null) {
                    // explicit @AttributeDefinition(id) remap: method called by normalized name
                    method = ocd.getPropertyMethods().values().stream()
                            .filter(m -> m.getNormalizedOsgiPropName()
                                    .equalsIgnoreCase(pending.methodName()))
                            .findFirst()
                            .orElse(null);
                }
                if (method == null) {
                    continue;
                }
                PendingConsumption.InvocationType pendingType = pending.invocationTypeName()
                        .flatMap(PendingConsumption.InvocationType::from)
                        .orElse(PendingConsumption.InvocationType.DIRECT_METHOD_CALL);
                ConsumesPropertyEdge.InvocationType invocationType =
                        ConsumesPropertyEdge.InvocationType.valueOf(pendingType.name());

                OSGiComponentNode component = componentsByFqn.get(pending.componentFqn());
                List<OSGiComponentNode> consumers = new ArrayList<>();
                if (component != null) {
                    consumers.add(component);
                } else {
                    for (OSGiComponentNode c : componentsByFqn.values()) {
                        if (c.getDesignatedOcdFqn().map(pending.ocdTypeName()::equals).orElse(false)) {
                            consumers.add(c);
                        }
                    }
                }
                for (OSGiComponentNode consumer : consumers) {
                    edges.add(new ConsumesPropertyEdge(consumer, method, pending.callSite(),
                            invocationType, pending.enclosingMethodName()));
                }
            }
        }

        // INJECTS_SERVICE
        for (JavaSourceParseResult java : input.javaResults()) {
            for (PendingServiceInjection pending : java.pendingServiceInjections()) {
                resolveInjection(pending, componentsByFqn, modelNodes, classNodes, edges);
            }
        }

        // ADAPTS_TO
        for (JavaSourceParseResult java : input.javaResults()) {
            for (PendingAdaptTo pending : java.pendingAdaptTo()) {
                JavaClassNode source = classNodes.get(pending.sourceClassFqn());
                if (source == null) {
                    source = graph.getIndex().findClassByFqn(pending.sourceClassFqn());
                }
                if (source == null) {
                    continue;
                }
                List<SlingModelNode> models = graph.getIndex().findModelsByAdapter(pending.targetTypeName());
                if (models.isEmpty()) {
                    SlingModelNode direct = modelNodes.get(pending.targetTypeName());
                    if (direct != null) {
                        models = List.of(direct);
                    }
                }
                if (models.isEmpty()) {
                    // Unresolved adapter FQN (wildcard import): fall back to a unique simple-name match.
                    String simpleName = pending.targetTypeName()
                            .substring(pending.targetTypeName().lastIndexOf('.') + 1);
                    List<SlingModelNode> bySimpleName = new ArrayList<>();
                    for (SlingModelNode model : modelNodes.values()) {
                        boolean matches = model.getAdapters().stream()
                                .anyMatch(adapter -> adapter.substring(adapter.lastIndexOf('.') + 1)
                                        .equals(simpleName));
                        if (matches) {
                            bySimpleName.add(model);
                        }
                    }
                    if (bySimpleName.size() == 1) {
                        models = bySimpleName;
                    }
                }
                for (SlingModelNode model : models) {
                    edges.add(new SimpleEdge(source, model, EdgeType.ADAPTS_TO,
                            source.getId() + " adapts to " + model.getId()));
                }
            }
        }

        // REFERENCES_CLASS (direct java-to-java syntactic references)
        for (JavaSourceParseResult java : input.javaResults()) {
            for (JavaClassNode node : java.javaClasses()) {
                for (String iface : node.getInterfaces()) {
                    JavaClassNode target = classNodes.get(iface);
                    if (target == null) {
                        target = graph.getIndex().findClassByFqn(iface);
                    }
                    if (target != null && target != node) {
                        edges.add(new SimpleEdge(node, target, EdgeType.REFERENCES_CLASS,
                                node.getId() + " references " + target.getId()));
                    }
                }
            }
        }

        // ── Phase D: batch commit + seal ───────────────────────────────────────
        Set<PolyglotEdge> unique = new LinkedHashSet<>(edges);
        for (PolyglotEdge edge : unique) {
            graph.addEdge(edge);
        }
        var sealed = graph.seal();

        // Collect code blocks for clone analysis, administrative API calls, resource leaks, and subservice calls
        List<io.github.rohithv16.aem.analyser.core.parser.java.CodeBlockSnapshot> allCodeBlocks = new ArrayList<>();
        List<io.github.rohithv16.aem.analyser.core.parser.java.AdministrativeCall> allAdminCalls = new ArrayList<>();
        List<io.github.rohithv16.aem.analyser.core.model.perf.ResourceLeakFinding> allLeaks = new ArrayList<>();
        List<io.github.rohithv16.aem.analyser.core.model.perf.QueryTraversalFinding> allTraversals = new ArrayList<>();
        List<io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall> allSubserviceCalls = new ArrayList<>();
        for (JavaSourceParseResult java : input.javaResults()) {
            allCodeBlocks.addAll(java.codeBlocks());
            allAdminCalls.addAll(java.administrativeCalls());
            allLeaks.addAll(java.resourceLeaks());
            allTraversals.addAll(java.queryTraversals());
            allSubserviceCalls.addAll(java.subserviceCalls());
        }

        return new GraphBuildResult(graph, unique, exportedPatterns, componentsByFqn,
                configNodes, classNodes, modelNodes, allCodeBlocks, allAdminCalls, allLeaks, allTraversals, allSubserviceCalls, sealed);
    }

    private List<OSGiComponentNode> resolveComponentForPid(String pid,
                                                           Map<String, OSGiComponentNode> componentsByFqn) {
        List<OSGiComponentNode> matches = new ArrayList<>();
        for (OSGiComponentNode component : componentsByFqn.values()) {
            List<String> activePids = ReferenceGraphIndex.computeActivePids(component);
            if (activePids.contains(pid)) {
                matches.add(component);
            }
        }
        return matches;
    }

    private OcdPropertyMethodNode findBoundMethod(OSGiOcdNode ocd, String propertyKey) {
        String normalizedKey = MetatypeNameNormalizer.normalizeMethodName(propertyKey, null);
        for (OcdPropertyMethodNode method : ocd.getPropertyMethods().values()) {
            if (method.getNormalizedOsgiPropName().equalsIgnoreCase(propertyKey)) {
                return method;
            }
            if (normalizedKey != null && method.getNormalizedOsgiPropName().equalsIgnoreCase(normalizedKey)) {
                return method;
            }
        }
        return null;
    }

    private void resolveInjection(PendingServiceInjection pending,
                                  Map<String, OSGiComponentNode> componentsByFqn,
                                  Map<String, SlingModelNode> modelNodes,
                                  Map<String, JavaClassNode> classNodes,
                                  List<PolyglotEdge> edges) {
        String targetType = cleanTargetType(pending.serviceTypeName());
        if (targetType == null || targetType.isBlank() || targetType.startsWith("java.")) {
            return;
        }
        List<PolyglotNode> consumers = injectionConsumers(pending.consumerFqn(), componentsByFqn,
                modelNodes, classNodes);
        if (consumers.isEmpty()) {
            return;
        }
        List<OSGiComponentNode> targets = new ArrayList<>();
        // direct implementation class reference
        OSGiComponentNode implComponent = componentsByFqn.get(targetType);
        if (implComponent != null) {
            targets.add(implComponent);
        }
        // service interface reference
        targets.addAll(graph.getIndex().findComponentsByServiceInterface(targetType));
        if (targets.isEmpty()) {
            // The FQN could not be resolved: wildcard imports and types living in the consumer's own
            // package both degrade to "<package>.<SimpleName>". Fall back to a UNIQUE simple-name
            // match so a genuine injection is not lost, without inventing edges on ambiguity.
            targets.addAll(findComponentsByUniqueServiceName(targetType, componentsByFqn));
        }
        for (OSGiComponentNode target : targets) {
            for (PolyglotNode source : consumers) {
                if (source.getId().equals(target.getId()) && source.getNodeType() == target.getNodeType()) {
                    continue; // self-reference does not count as external consumption
                }
                edges.add(new SimpleEdge(source, target, EdgeType.INJECTS_SERVICE,
                        source.getId() + " injects service " + target.getId()));
            }
        }
    }

    /**
     * Resolves the types that consume an injection. A {@code @Reference} declared in a plain base
     * class (the usual abstract-servlet layout) is attributed to every component that extends it.
     */
    private List<PolyglotNode> injectionConsumers(String consumerFqn,
                                                  Map<String, OSGiComponentNode> componentsByFqn,
                                                  Map<String, SlingModelNode> modelNodes,
                                                  Map<String, JavaClassNode> classNodes) {
        List<PolyglotNode> consumers = new ArrayList<>();
        OSGiComponentNode component = componentsByFqn.get(consumerFqn);
        if (component != null) {
            consumers.add(component);
        }
        SlingModelNode model = modelNodes.get(consumerFqn);
        if (model != null) {
            consumers.add(model);
        }
        if (!consumers.isEmpty()) {
            return consumers;
        }
        for (OSGiComponentNode candidate : componentsByFqn.values()) {
            if (extendsType(candidate.getFqn(), consumerFqn, componentsByFqn, classNodes)) {
                consumers.add(candidate);
            }
        }
        return consumers;
    }

    /** Walks the superclass chain (components + plain classes) looking for {@code ancestorFqn}. */
    private boolean extendsType(String fqn, String ancestorFqn,
                                Map<String, OSGiComponentNode> componentsByFqn,
                                Map<String, JavaClassNode> classNodes) {
        Set<String> seen = new HashSet<>();
        String current = fqn;
        for (int depth = 0; depth < 32 && current != null && seen.add(current); depth++) {
            String superClass = null;
            JavaClassNode classNode = classNodes.get(current);
            if (classNode != null) {
                superClass = classNode.getSuperClass().orElse(null);
            }
            if (superClass == null) {
                OSGiComponentNode component = componentsByFqn.get(current);
                if (component != null) {
                    superClass = component.getSuperClass().orElse(null);
                }
            }
            if (superClass == null) {
                return false;
            }
            if (superClass.equals(ancestorFqn)) {
                return true;
            }
            current = superClass;
        }
        return false;
    }

    /** Components exposing a service interface whose simple name matches, only when unique. */
    private List<OSGiComponentNode> findComponentsByUniqueServiceName(
            String typeName, Map<String, OSGiComponentNode> componentsByFqn) {
        String simpleName = typeName.substring(typeName.lastIndexOf('.') + 1);
        List<OSGiComponentNode> matches = new ArrayList<>();
        for (OSGiComponentNode component : componentsByFqn.values()) {
            boolean exposes = component.getServiceInterfaces().stream()
                    .anyMatch(iface -> iface.substring(iface.lastIndexOf('.') + 1).equals(simpleName));
            if (exposes) {
                matches.add(component);
            }
        }
        return matches.size() == 1 ? matches : List.of();
    }

    private String cleanTargetType(String raw) {
        if (raw == null) {
            return null;
        }
        String type = raw.strip();
        int generic = type.indexOf('<');
        if (generic > 0) {
            type = type.substring(0, generic).strip();
        }
        if (type.endsWith("[]")) {
            type = type.substring(0, type.length() - 2);
        }
        int space = type.indexOf(' ');
        if (space > 0) {
            type = type.substring(0, space).strip();
        }
        return type;
    }

    private OSGiConfigNode toConfigNode(OsgiConfigDefinition definition,
                                        Map<String, ConfigPropertyNode> configPropertyNodes,
                                        Map<String, OSGiComponentNode> componentsByFqn) {
        Path filePath = definition.filePath();
        String pid = definition.pid();
        String factorySubname = definition.factoryIdentifier();
        boolean isFactory = definition.isFactory();

        // Legacy .config file names are ambiguous when both the PID and the factory subname may
        // contain hyphens. If the whole base name binds to exactly one component and the split PID
        // binds to none, the hyphen belongs to the PID, not to a factory delimiter.
        if (definition.factoryAmbiguous() && pid != null && factorySubname != null) {
            String wholePid = pid + "-" + factorySubname;
            List<OSGiComponentNode> splitMatches = resolveComponentForPid(pid, componentsByFqn);
            List<OSGiComponentNode> wholeMatches = resolveComponentForPid(wholePid, componentsByFqn);
            if (wholeMatches.size() == 1 && splitMatches.isEmpty()) {
                pid = wholePid;
                factorySubname = null;
                isFactory = false;
            }
        }

        OSGiConfigNode node = new OSGiConfigNode(
                filePath.toString(), filePath, null,
                pid, factorySubname, isFactory,
                definition.runmodes(), definition.format());
        for (var entry : definition.properties().values()) {
            ConfigPropertyNode propertyNode = new ConfigPropertyNode(
                    node.getId(), filePath,
                    new io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation(filePath,
                            entry.line(), entry.column()),
                    entry.baseKey(), entry.rawKey(), entry.typeHint(),
                    entry.rawValue(), entry.inferredType(), entry.isEnvPlaceholder());
            node.addProperty(propertyNode);
        }
        return node;
    }

    public record GraphBuildResult(ReferenceGraph graph,
                                   Set<PolyglotEdge> resolvedEdges,
                                   Set<String> exportedPatterns,
                                   Map<String, OSGiComponentNode> componentsByFqn,
                                   Map<String, OSGiConfigNode> configNodes,
                                   Map<String, JavaClassNode> classNodes,
                                   Map<String, SlingModelNode> modelNodes,
                                   List<io.github.rohithv16.aem.analyser.core.parser.java.CodeBlockSnapshot> codeBlocks,
                                   List<io.github.rohithv16.aem.analyser.core.parser.java.AdministrativeCall> administrativeCalls,
                                   List<io.github.rohithv16.aem.analyser.core.model.perf.ResourceLeakFinding> resourceLeaks,
                                   List<io.github.rohithv16.aem.analyser.core.model.perf.QueryTraversalFinding> queryTraversals,
                                   List<io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall> subserviceCalls,
                                   org.jgrapht.Graph<PolyglotNode, PolyglotEdge> sealedGraph) {

        public GraphBuildResult(ReferenceGraph graph,
                                Set<PolyglotEdge> resolvedEdges,
                                Set<String> exportedPatterns,
                                Map<String, OSGiComponentNode> componentsByFqn,
                                Map<String, OSGiConfigNode> configNodes,
                                Map<String, JavaClassNode> classNodes,
                                Map<String, SlingModelNode> modelNodes,
                                List<io.github.rohithv16.aem.analyser.core.parser.java.CodeBlockSnapshot> codeBlocks,
                                List<io.github.rohithv16.aem.analyser.core.parser.java.AdministrativeCall> administrativeCalls,
                                List<io.github.rohithv16.aem.analyser.core.model.perf.ResourceLeakFinding> resourceLeaks,
                                List<io.github.rohithv16.aem.analyser.core.model.perf.QueryTraversalFinding> queryTraversals,
                                org.jgrapht.Graph<PolyglotNode, PolyglotEdge> sealedGraph) {
            this(graph, resolvedEdges, exportedPatterns, componentsByFqn, configNodes, classNodes, modelNodes,
                    codeBlocks, administrativeCalls, resourceLeaks, queryTraversals, List.of(), sealedGraph);
        }

        public GraphBuildResult(ReferenceGraph graph,
                                Set<PolyglotEdge> resolvedEdges,
                                Set<String> exportedPatterns,
                                Map<String, OSGiComponentNode> componentsByFqn,
                                Map<String, OSGiConfigNode> configNodes,
                                Map<String, JavaClassNode> classNodes,
                                Map<String, SlingModelNode> modelNodes,
                                List<io.github.rohithv16.aem.analyser.core.parser.java.CodeBlockSnapshot> codeBlocks,
                                List<io.github.rohithv16.aem.analyser.core.parser.java.AdministrativeCall> administrativeCalls,
                                org.jgrapht.Graph<PolyglotNode, PolyglotEdge> sealedGraph) {
            this(graph, resolvedEdges, exportedPatterns, componentsByFqn, configNodes, classNodes, modelNodes,
                    codeBlocks, administrativeCalls, List.of(), List.of(), sealedGraph);
        }

        public GraphBuildResult(ReferenceGraph graph,
                                Set<PolyglotEdge> resolvedEdges,
                                Set<String> exportedPatterns,
                                Map<String, OSGiComponentNode> componentsByFqn,
                                Map<String, OSGiConfigNode> configNodes,
                                Map<String, JavaClassNode> classNodes,
                                Map<String, SlingModelNode> modelNodes,
                                List<io.github.rohithv16.aem.analyser.core.parser.java.CodeBlockSnapshot> codeBlocks,
                                org.jgrapht.Graph<PolyglotNode, PolyglotEdge> sealedGraph) {
            this(graph, resolvedEdges, exportedPatterns, componentsByFqn, configNodes, classNodes, modelNodes,
                    codeBlocks, List.of(), List.of(), List.of(), sealedGraph);
        }
    }
}
