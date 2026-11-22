package io.github.rohithv16.aem.analyser.core.parser.config;

import io.github.rohithv16.aem.analyser.core.discovery.FileVaultPluginConfig;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.ModelReader;
import org.apache.maven.model.io.DefaultModelReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the FileVault plugin configuration of every reactor module, keyed by artifact id.
 *
 * <p>Module discovery keeps the classification, not the plugin configuration, so the embed graph a
 * container's {@code all/pom.xml} declares cannot be reconstructed from {@code ModuleDescriptor}
 * alone. Re-reading the module POM it already points at costs one small file per module — unlike
 * the tree walks {@code FileInventory} exists to eliminate — and keeps this rule independent of the
 * engine's wiring.
 *
 * <p>The plugin is looked up in {@code <build>} and in every profile, because the AEM archetype's
 * {@code autoInstallSinglePackage} profile re-declares it.
 */
public final class PackageEmbedGraphParser {

    private static final String FILEVAULT_PLUGIN_ARTIFACT = "filevault-package-maven-plugin";

    private final ModelReader modelReader = new DefaultModelReader();

    /** @return the FileVault configuration of each module that declares the plugin */
    public Map<String, FileVaultPluginConfig> scan(List<ModuleDescriptor> modules) {
        Map<String, FileVaultPluginConfig> byArtifactId = new LinkedHashMap<>();
        if (modules == null) {
            return byArtifactId;
        }
        for (ModuleDescriptor module : modules) {
            String artifactId = module.artifactId();
            if (artifactId == null || module.pomPath() == null
                    || !Files.isRegularFile(module.pomPath())) {
                continue;
            }
            findPlugin(module).ifPresent(plugin ->
                    byArtifactId.put(artifactId,
                            FileVaultPluginConfig.fromPluginConfiguration(artifactId, plugin.getConfiguration())));
        }
        return byArtifactId;
    }

    private java.util.Optional<Plugin> findPlugin(ModuleDescriptor module) {
        Model model;
        try {
            model = modelReader.read(module.pomPath().toFile(), Map.of(ModelReader.IS_STRICT, false));
        } catch (Exception unreadable) {
            return java.util.Optional.empty();
        }
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
                .filter(plugin -> FILEVAULT_PLUGIN_ARTIFACT.equals(plugin.getArtifactId()))
                .findFirst();
    }

    /** 1-based line of the first occurrence of {@code needle} in the module POM, or {@code 1}. */
    public int lineOf(Path pomPath, String needle) {
        if (pomPath == null || needle == null) {
            return 1;
        }
        try {
            List<String> lines = Files.readAllLines(pomPath);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(needle)) {
                    return i + 1;
                }
            }
        } catch (Exception unreadable) {
            // A POM that was readable during discovery but not now: report at the file head.
        }
        return 1;
    }
}
