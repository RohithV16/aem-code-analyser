package io.github.rohithv16.aem.analyser.core.graph;

import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiOcdNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OcdPropertyMethodNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.SlingModelNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * FEAT-GRAPH-02: secondary concurrent in-memory lookup indices providing O(1)
 * cross-boundary resolution without touching the graph structure.
 */
public final class ReferenceGraphIndex {

    /** PID or factory base PID -> components with that PID. */
    private final Map<String, List<OSGiComponentNode>> pidToComponents = new ConcurrentHashMap<>();
    /** OCD FQN or PID -> OCD node. */
    private final Map<String, OSGiOcdNode> ocdLookup = new ConcurrentHashMap<>();
    /** Service interface FQN -> components implementing it. */
    private final Map<String, List<OSGiComponentNode>> serviceInterfaceIndex = new ConcurrentHashMap<>();
    /** resourceType -> Sling Models bound to it. */
    private final Map<String, List<SlingModelNode>> resourceTypeToModels = new ConcurrentHashMap<>();
    /** Adapter interface FQN -> Sling Models implementing it. */
    private final Map<String, List<SlingModelNode>> adapterToModels = new ConcurrentHashMap<>();
    /** Class FQN -> node. */
    private final Map<String, JavaClassNode> classLookup = new ConcurrentHashMap<>();
    /** Runmode -> OSGi configs. */
    private final Map<String, List<OSGiConfigNode>> runmodeIndex = new ConcurrentHashMap<>();

    public void registerComponent(OSGiComponentNode component) {
        computeActivePids(component).forEach(pid ->
                pidToComponents.computeIfAbsent(pid, k -> new ArrayList<>()).add(component));
        component.getServiceInterfaces().forEach(iface ->
                serviceInterfaceIndex.computeIfAbsent(iface, k -> new ArrayList<>()).add(component));
        classLookup.put(component.getFqn(), component);
    }

    public void registerOcd(OSGiOcdNode ocd) {
        ocdLookup.put(ocd.getOcdFqn(), ocd);
        ocdLookup.putIfAbsent(ocd.getPid(), ocd);
        classLookup.put(ocd.getFqn(), ocd);
    }

    public void registerSlingModel(SlingModelNode model) {
        model.getResourceType().ifPresent(rt ->
                resourceTypeToModels.computeIfAbsent(rt, k -> new ArrayList<>()).add(model));
        model.getAdapters().forEach(adapter ->
                adapterToModels.computeIfAbsent(adapter, k -> new ArrayList<>()).add(model));
        classLookup.put(model.getFqn(), model);
    }

    public void registerJavaClass(JavaClassNode node) {
        classLookup.putIfAbsent(node.getFqn(), node);
    }

    public void registerConfig(OSGiConfigNode config) {
        if (config.getPid() == null) {
            return;
        }
        // A config can be active in several runmodes (e.g. config.author.dev). Index it under
        // every individual runmode so single-runmode lookups resolve; a combined "author+dev"
        // key can never be hit by getConfigsForRunmode("author").
        if (config.getRunmodes().isEmpty()) {
            runmodeIndex.computeIfAbsent("", k -> new ArrayList<>()).add(config);
            return;
        }
        config.getRunmodes().stream()
                .filter(runmode -> runmode != null && !runmode.isBlank())
                .map(runmode -> runmode.toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .forEach(runmode -> runmodeIndex.computeIfAbsent(runmode, k -> new ArrayList<>()).add(config));
    }

    
    public OSGiOcdNode findOcdByFqn(String fqn) {
        return ocdLookup.get(fqn);
    }

    public OSGiOcdNode findOcd(String fqnOrPid) {
        return ocdLookup.get(fqnOrPid);
    }

    public Map<String, OSGiOcdNode> getOcdLookup() {
        return Map.copyOf(ocdLookup);
    }

    public List<OSGiComponentNode> findComponentsByServiceInterface(String iface) {
        return List.copyOf(serviceInterfaceIndex.getOrDefault(iface, List.of()));
    }

    public List<SlingModelNode> findModelsByResourceType(String resourceType) {
        return List.copyOf(resourceTypeToModels.getOrDefault(resourceType, List.of()));
    }

    public List<SlingModelNode> findModelsByAdapter(String adapterFqn) {
        return List.copyOf(adapterToModels.getOrDefault(adapterFqn, List.of()));
    }

    public JavaClassNode findClassByFqn(String fqn) {
        return classLookup.get(fqn);
    }

    public List<OSGiConfigNode> getConfigsForRunmode(String runmode) {
        return List.copyOf(runmodeIndex.getOrDefault(runmode.toLowerCase(Locale.ROOT), List.of()));
    }

    /**
     * OSGi DS 1.4 §112.8.2 ActivePids(K) with default PID suppression and "$" expansion.
     *
     * <p>A factory component's configurations are named {@code <factoryPid>~<instance>}, so the
     * factory id itself is an active PID too - omitting it reported every factory configuration as
     * orphaned.
     */
    public static List<String> computeActivePids(OSGiComponentNode component) {
        if (component.getConfigurationPolicy() == OSGiComponentNode.ConfigurationPolicy.IGNORE) {
            return List.of();
        }
        String defaultPid = component.getName()
                .filter(n -> !n.isBlank())
                .orElse(component.getComponentClassFqn());
        java.util.LinkedHashSet<String> active = new java.util.LinkedHashSet<>();
        List<String> configPids = component.getConfigurationPids();
        if (configPids != null && !configPids.isEmpty()) {
            configPids.stream()
                    .map(pid -> pid.replace("$", defaultPid))
                    .forEach(active::add);
        } else {
            active.add(defaultPid);
        }
        component.getFactoryId()
                .filter(factory -> !factory.isBlank())
                .map(factory -> factory.replace("$", defaultPid))
                .ifPresent(active::add);
        return List.copyOf(active);
    }

    }
