package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.discovery.CloudConfigDescriptor;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleClassification;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.ModelReader;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    private final ModelReader modelReader;
    private final ModuleClassifier classifier = new ModuleClassifier();
    private final OsgiConfigScanner osgiConfigScanner = new OsgiConfigScanner();
    private final CloudConfigDiscoveryEngine cloudConfigEngine = new CloudConfigDiscoveryEngine();
    private List<String> activeProfiles = List.of();

    public ProjectDiscoveryEngine() {
        this.modelReader = new org.apache.maven.model.io.DefaultModelReader();
    }

    public DiscoveryResult discover(Path projectRoot, List<String> activeProfiles) {
        this.activeProfiles = activeProfiles == null ? List.of() : activeProfiles;
        Path rootPom = projectRoot.resolve("pom.xml");
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
            collectChildModulePoms(model, moduleDirectory, queue);

            modules.add(buildDescriptor(model, pomPath, moduleDirectory, activeProfiles));
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

    private void collectChildModulePoms(Model model, Path moduleDirectory, Deque<Path> queue) {
        for (String module : collectModules(model)) {
            Path childDir = moduleDirectory.resolve(module).normalize();
            Path childPom = Files.isRegularFile(childDir.resolve("pom.xml"))
                    ? childDir.resolve("pom.xml")
                    : childDir;
            if (Files.isRegularFile(childPom)) {
                queue.add(childPom);
            }
        }
    }

    /**
     * Collects modules of always-active profiles plus explicitly activated profiles.
     */
    private List<String> collectModules(Model model) {
        List<String> modules = new ArrayList<>(model.getModules());
        for (Profile profile : model.getProfiles()) {
            boolean activeByDefault = profile.getActivation() != null
                    && profile.getActivation().isActiveByDefault();
            boolean explicitlyActivated = activeProfiles != null
                    && !activeProfiles.isEmpty()
                    && activeProfiles.contains(profile.getId());
            if (activeByDefault || explicitlyActivated) {
                modules.addAll(profile.getModules());
            }
        }
        return modules;
    }

    private ModuleDescriptor buildDescriptor(Model model, Path pomPath, Path moduleDirectory,
                                             List<String> activeProfiles) {
        String artifactId = model.getArtifactId() != null ? model.getArtifactId() : moduleDirectory.getFileName().toString();
        String packaging = model.getPackaging() != null ? model.getPackaging() : "jar";
        String groupId = model.getGroupId() != null
                ? model.getGroupId()
                : (model.getParent() != null ? model.getParent().getGroupId() : null);

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

        List<OsgiConfigScanner.DiscoveredConfig> configs = osgiConfigScanner.scanModule(moduleDirectory);
        Set<Path> configPaths = new LinkedHashSet<>();
        configs.forEach(c -> configPaths.add(c.absolutePath()));

        ModuleClassification classification = classifier.classify(new ModuleClassifier.ClassificationInput(
                artifactId, packaging, moduleDirectory,
                !collectModules(model).isEmpty(),
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
                ExportPackageCollector.collect(model, moduleDirectory));
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
