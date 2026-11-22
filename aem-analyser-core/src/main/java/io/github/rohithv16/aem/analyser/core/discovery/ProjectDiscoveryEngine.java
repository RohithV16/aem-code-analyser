package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.discovery.CloudConfigDescriptor;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleClassification;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.ModelReader;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Generic recursive Maven reactor discovery engine (FEAT-DISC-01/02).
 * Traverses nested POM hierarchies at arbitrary depth without hardcoded module paths,
 * resolving parent-child inheritance and default-active profiles.
 */
public final class ProjectDiscoveryEngine implements AutoCloseable {

    private static final String FILEVAULT_PLUGIN_ARTIFACT = "filevault-package-maven-plugin";
    private static final String BUNDLE_PLUGIN_ARTIFACT = "maven-bundle-plugin";
    private static final String BND_PLUGIN_ARTIFACT = "bnd-maven-plugin";
    private static final String AEM_SDK_API = "aem-sdk-api";
    private static final String UBER_JAR = "uber-jar";
    private static final String POM_FILE = "pom.xml";

    private final ModelReader modelReader;
    private final ModuleClassifier classifier = new ModuleClassifier();
    private final OsgiConfigScanner osgiConfigScanner = new OsgiConfigScanner();
    private final CloudConfigDiscoveryEngine cloudConfigEngine = new CloudConfigDiscoveryEngine();
    private final io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics;
    private final FileInventory inventory;
    private List<String> activeProfiles = List.of();

    public ProjectDiscoveryEngine() {
        this(new io.github.rohithv16.aem.analyser.core.util.DiagnosticSink());
    }

    public ProjectDiscoveryEngine(io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics) {
        this(null, diagnostics);
    }

    /**
     * @param inventory the run's shared pruned inventory, or {@code null} to let each config scan
     *                  build its own for the subtree it needs
     */
    public ProjectDiscoveryEngine(FileInventory inventory,
                                  io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics) {
        this.modelReader = new org.apache.maven.model.io.DefaultModelReader();
        this.inventory = inventory;
        this.diagnostics = diagnostics == null
                ? new io.github.rohithv16.aem.analyser.core.util.DiagnosticSink()
                : diagnostics;
    }

    public DiscoveryResult discover(Path projectRoot, List<String> activeProfiles) {
        this.activeProfiles = activeProfiles == null ? List.of() : activeProfiles;
        Path rootPom = projectRoot.resolve(POM_FILE);
        if (!Files.isRegularFile(rootPom)) {
            throw new IllegalArgumentException("No root pom.xml found under " + projectRoot);
        }

        List<ModuleDescriptor> modules = new ArrayList<>();
        Map<Path, Model> modelsByPath = new LinkedHashMap<>();
        Set<Path> visited = new HashSet<>();

        Deque<Path> queue = new ArrayDeque<>();
        queue.add(rootPom);

        while (!queue.isEmpty()) {
            Path pomPath = queue.poll();
            if (!visited.add(pomPath.toAbsolutePath().normalize())) {
                continue;
            }
            Model model = readModel(pomPath);
            modelsByPath.put(pomPath.toAbsolutePath().normalize(), model);

            Path moduleDirectory = pomPath.getParent();
            List<String> modelModules = collectModules(model);
            collectChildModulePoms(modelModules, moduleDirectory, queue);

            modules.add(buildDescriptor(model, pomPath, moduleDirectory, modelModules));
        }

        List<CloudConfigDescriptor> cloudConfigs = cloudConfigEngine.discover(projectRoot);
        return new DiscoveryResult(List.copyOf(modules), cloudConfigs, Map.copyOf(modelsByPath));
    }

    private Model readModel(Path pomPath) {
        try {
            return modelReader.read(pomPath.toFile(), Map.of(ModelReader.IS_STRICT, false));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse POM " + pomPath + ": " + e.getMessage(), e);
        }
    }

    private void collectChildModulePoms(List<String> modelModules, Path moduleDirectory, Deque<Path> queue) {
        for (String module : modelModules) {
            Path childDir = moduleDirectory.resolve(module).normalize();
            Path childPom = Files.isRegularFile(childDir.resolve(POM_FILE))
                    ? childDir.resolve(POM_FILE)
                    : childDir;
            if (Files.isRegularFile(childPom)) {
                queue.add(childPom);
            }
        }
    }

    /**
     * Collects the effective module list using Maven profile semantics: explicitly activated
     * profiles win, and {@code activeByDefault} profiles are ignored for this POM as soon as any
     * profile of the same POM is activated explicitly.
     */
    private List<String> collectModules(Model model) {
        Set<String> modules = new java.util.LinkedHashSet<>(model.getModules());
        List<Profile> profiles = model.getProfiles();
        if (profiles == null || profiles.isEmpty()) {
            return List.copyOf(modules);
        }

        Set<String> requested = activeProfiles == null ? Set.of() : Set.copyOf(activeProfiles);
        boolean anyExplicitlyActivated = profiles.stream().anyMatch(p -> requested.contains(p.getId()));

        for (Profile profile : profiles) {
            boolean explicitlyActivated = requested.contains(profile.getId());
            boolean activeByDefault = profile.getActivation() != null
                    && profile.getActivation().isActiveByDefault();
            if (explicitlyActivated || (activeByDefault && !anyExplicitlyActivated)) {
                modules.addAll(profile.getModules());
            }
        }
        return List.copyOf(modules);
    }

    private ModuleDescriptor buildDescriptor(Model model, Path pomPath, Path moduleDirectory,
                                             List<String> modelModules) {
        String artifactId = model.getArtifactId() != null ? model.getArtifactId() : moduleDirectory.getFileName().toString();
        String packaging = model.getPackaging() != null ? model.getPackaging() : "jar";
        String groupId = model.getGroupId();
        if (groupId == null && model.getParent() != null) {
            groupId = model.getParent().getGroupId();
        }

        FileVaultPluginConfig fvConfig = findPlugin(model, FILEVAULT_PLUGIN_ARTIFACT)
                .map(p -> FileVaultPluginConfig.fromPluginConfiguration(artifactId, p.getConfiguration()))
                .orElseGet(() -> FileVaultPluginConfig.fromPluginConfiguration(artifactId, null));
        boolean hasBndPlugin = findPlugin(model, BND_PLUGIN_ARTIFACT).isPresent()
                || findPlugin(model, BUNDLE_PLUGIN_ARTIFACT).isPresent()
                || Files.isRegularFile(moduleDirectory.resolve("bnd.bnd"));
        boolean hasFilters = (model.getBuild() != null && !model.getBuild().getFilters().isEmpty())
                || Files.isRegularFile(moduleDirectory.resolve("src/main/content/META-INF/vault/filter.xml"));
        boolean dependsOnAemSdk = model.getDependencies().stream().anyMatch(d ->
                        AEM_SDK_API.equals(d.getArtifactId()) || UBER_JAR.equals(d.getArtifactId()))
                || (model.getDependencyManagement() != null && model.getDependencyManagement().getDependencies().stream()
                        .anyMatch(d -> AEM_SDK_API.equals(d.getArtifactId()) || UBER_JAR.equals(d.getArtifactId())));

        List<OsgiConfigScanner.DiscoveredConfig> configs = inventory != null && !inventory.isEmpty()
                ? osgiConfigScanner.scanModule(inventory, moduleDirectory, diagnostics)
                : osgiConfigScanner.scanModule(moduleDirectory, diagnostics);
        Set<Path> configPaths = new LinkedHashSet<>();
        configs.forEach(c -> configPaths.add(c.absolutePath()));

        ModuleClassification classification = classifier.classify(new ModuleClassifier.ClassificationInput(
                artifactId, packaging, moduleDirectory,
                !modelModules.isEmpty(),
                fvConfig,
                !configPaths.isEmpty(),
                hasBndPlugin,
                hasFilters,
                dependsOnAemSdk));

        return new ModuleDescriptor(
                artifactId,
                groupId,
                packaging,
                moduleDirectory.toAbsolutePath().normalize(),
                pomPath.toAbsolutePath().normalize(),
                classification,
                model.getParent() == null ? null : model.getParent().getArtifactId(),
                !collectModules(model).isEmpty(),
                configPaths,
                ExportPackageCollector.collect(model, moduleDirectory),
                detectBundleSymbolicName(model, moduleDirectory));
    }

    /**
     * Best-effort Bundle-SymbolicName from {@code bnd.bnd} or the maven-bundle-plugin instructions.
     */
    private String detectBundleSymbolicName(Model model, Path moduleDirectory) {
        String fromBndFile = bundleSymbolicNameFromBndFile(moduleDirectory);
        return fromBndFile != null ? fromBndFile : bundleSymbolicNameFromPlugin(model);
    }

    /** The {@code bnd.bnd} declaration, or {@code null} when the file is absent or does not declare one. */
    private String bundleSymbolicNameFromBndFile(Path moduleDirectory) {
        Path bnd = moduleDirectory.resolve("bnd.bnd");
        if (!Files.isRegularFile(bnd)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(bnd)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("Bundle-SymbolicName:")) {
                    String value = trimmed.substring("Bundle-SymbolicName:".length()).strip();
                    int directive = value.indexOf(';');
                    if (directive > 0) {
                        value = value.substring(0, directive);
                    }
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
        } catch (IOException unreadableBnd) {
            // An unreadable bnd.bnd falls through to the POM instructions below.
        }
        return null;
    }

    /** The bundle-plugin {@code <instructions>} declaration, or {@code null} when absent. */
    private String bundleSymbolicNameFromPlugin(Model model) {
        List<Plugin> plugins = model.getBuild() == null ? List.of() : model.getBuild().getPlugins();
        for (Plugin plugin : plugins) {
            if (plugin.getConfiguration() instanceof Xpp3Dom configuration) {
                Xpp3Dom instructions = configuration.getChild("instructions");
                Xpp3Dom symbolicName = instructions == null
                        ? null
                        : instructions.getChild("Bundle-SymbolicName");
                if (symbolicName != null && symbolicName.getValue() != null
                        && !symbolicName.getValue().isBlank()) {
                    return symbolicName.getValue().strip();
                }
            }
        }
        return null;
    }

    private Optional<Plugin> findPlugin(Model model, String artifactId) {
        List<Plugin> candidates = new ArrayList<>();
        if (model.getBuild() != null) {
            candidates.addAll(model.getBuild().getPlugins());
        }
        for (Profile profile : model.getProfiles()) {
            if (profile.getBuild() != null) {
                candidates.addAll(profile.getBuild().getPlugins());
            }
        }
        return candidates.stream()
                .filter(p -> artifactId.equals(p.getArtifactId()))
                .findFirst();
    }

    @Override
    public void close() {
        // ModelReader holds no persistent resources; kept for symmetric resource handling.
    }

    public record DiscoveryResult(List<ModuleDescriptor> modules,
                                  List<CloudConfigDescriptor> cloudConfigs,
                                  Map<Path, Model> modelsByPath) {
    }
}
