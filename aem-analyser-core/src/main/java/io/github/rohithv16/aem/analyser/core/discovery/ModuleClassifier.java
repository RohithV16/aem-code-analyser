package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleClassification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Deterministic 11-category semantic module classifier (spec §5.3.1 decision tree).
 */
public final class ModuleClassifier {

    private final OsgiConfigScanner osgiConfigScanner = new OsgiConfigScanner();

    public ModuleClassification classify(ClassificationInput input) {
        String packaging = input.packaging() == null ? "jar" : input.packaging().toLowerCase(Locale.ROOT);
        String artifactId = input.artifactId() == null ? "" : input.artifactId().toLowerCase(Locale.ROOT);
        String pathText = input.moduleDirectory() == null ? "" : input.moduleDirectory().toString().toLowerCase(Locale.ROOT);
        boolean idOrPathMentionsDispatcher = artifactId.contains("dispatcher") || pathText.contains("dispatcher");
        boolean idOrPathMentionsPipeline = artifactId.contains("pipeline") || pathText.contains("pipeline");

        switch (packaging) {
            case "pom" -> {
                if (input.hasModules()) {
                    return ModuleClassification.AGGREGATOR_MODULE;
                }
                if (hasFrontendBuild(input.moduleDirectory())) {
                    return ModuleClassification.FRONTEND_MODULE;
                }
                if (idOrPathMentionsDispatcher) {
                    return ModuleClassification.DISPATCHER_MODULE;
                }
                if (idOrPathMentionsPipeline) {
                    return ModuleClassification.PIPELINE_MODULE;
                }
                if (isTestModule(artifactId) && !input.hasBndPlugin()) {
                    return ModuleClassification.TEST_MODULE;
                }
                return ModuleClassification.PIPELINE_MODULE;
            }
            case "content-package" -> {
                return classifyContentPackage(input, artifactId);
            }
            case "jar", "bundle" -> {
                if (hasFrontendBuild(input.moduleDirectory())) {
                    return ModuleClassification.FRONTEND_MODULE;
                }
                if (isTestModule(artifactId) && !input.hasBndPlugin()) {
                    return ModuleClassification.TEST_MODULE;
                }
                if (packaging.equals("bundle") || input.hasBndPlugin() || input.dependsOnAemSdk()) {
                    return ModuleClassification.BUNDLE_MODULE;
                }
                return ModuleClassification.BUNDLE_MODULE;
            }
            default -> {
                if (idOrPathMentionsDispatcher) {
                    return ModuleClassification.DISPATCHER_MODULE;
                }
                if (idOrPathMentionsPipeline) {
                    return ModuleClassification.PIPELINE_MODULE;
                }
                if (isTestModule(artifactId)) {
                    return ModuleClassification.TEST_MODULE;
                }
                return ModuleClassification.UNCLASSIFIED;
            }
        }
    }

    private ModuleClassification classifyContentPackage(ClassificationInput input, String artifactId) {
        // Condition 1: embeddeds are the definitive signature of the 'all' container
        if (input.fileVaultConfig() != null && input.fileVaultConfig().hasEmbeddeds()) {
            return ModuleClassification.CONTAINER_MODULE;
        }
        // Condition 2: physical OSGi config files under strict osgiconfig/ anchoring identify ui.config
        if (input.hasValidOsgiConfigs()) {
            return ModuleClassification.CONFIG_MODULE;
        }
        // Condition 3: naming conventions when the project has not been built
        if (artifactId.equals("all") || artifactId.endsWith(".all")) {
            return ModuleClassification.CONTAINER_MODULE;
        }
        if (artifactId.contains("ui.config") || artifactId.endsWith(".config")) {
            return ModuleClassification.CONFIG_MODULE;
        }
        if (artifactId.contains("structure")) {
            return ModuleClassification.STRUCTURE_MODULE;
        }
        if (artifactId.contains("ui.acl") || artifactId.endsWith(".acl")) {
            return ModuleClassification.ACL_MODULE;
        }
        if (artifactId.contains("ui.content") || artifactId.endsWith(".content")) {
            return ModuleClassification.CONTENT_MODULE;
        }
        if (artifactId.contains("ui.apps") || artifactId.endsWith(".apps")) {
            return ModuleClassification.APPS_MODULE;
        }
        String packageType = input.fileVaultConfig() == null ? null : input.fileVaultConfig().packageType();
        if (packageType != null) {
            return switch (packageType.toLowerCase(Locale.ROOT)) {
                case "application" -> ModuleClassification.APPS_MODULE;
                case "mixed" -> ModuleClassification.ACL_MODULE;
                case "content" -> ModuleClassification.CONTENT_MODULE;
                case "container" -> ModuleClassification.CONTAINER_MODULE;
                default -> ModuleClassification.UNCLASSIFIED;
            };
        }
        // Fallback: physical jcr_root content types
        if (hasJcrSubdirectory(input.moduleDirectory(), "components")
                || hasJcrSubdirectory(input.moduleDirectory(), "dialog")) {
            return ModuleClassification.APPS_MODULE;
        }
        if (hasJcrSubdirectory(input.moduleDirectory(), "content")
                || hasJcrSubdirectory(input.moduleDirectory(), "conf")) {
            return ModuleClassification.CONTENT_MODULE;
        }
        if (hasFiltersWithoutJcrRoot(input)) {
            return ModuleClassification.STRUCTURE_MODULE;
        }
        return ModuleClassification.UNCLASSIFIED;
    }

    private boolean isTestModule(String artifactId) {
        return artifactId.contains("it.tests") || artifactId.contains("it-tests")
                || artifactId.endsWith(".tests") || artifactId.contains("cypress")
                || artifactId.endsWith("-tests");
    }

    private boolean hasFrontendBuild(Path moduleDirectory) {
        if (moduleDirectory == null) {
            return false;
        }
        return Files.isRegularFile(moduleDirectory.resolve("package.json"))
                || Files.isRegularFile(moduleDirectory.resolve("package-lock.json"));
    }

    private boolean hasJcrSubdirectory(Path moduleDirectory, String name) {
        if (moduleDirectory == null) {
            return false;
        }
        Path jcrRoot = moduleDirectory.resolve("src/main/content/jcr_root/apps");
        if (!Files.isDirectory(jcrRoot)) {
            jcrRoot = moduleDirectory.resolve("src/main/content/jcr_root");
        }
        if (!Files.isDirectory(jcrRoot)) {
            return false;
        }
        try (var stream = Files.list(jcrRoot)) {
            return stream.anyMatch(p -> Files.isDirectory(p.resolve(name)));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasFiltersWithoutJcrRoot(ClassificationInput input) {
        return input.hasFilters() && !hasJcrRootDirectory(input.moduleDirectory());
    }

    private boolean hasJcrRootDirectory(Path moduleDirectory) {
        return moduleDirectory != null
                && Files.isDirectory(moduleDirectory.resolve("src/main/content/jcr_root"));
    }

    /**
     * Immutable classification input contract.
     */
    public record ClassificationInput(
            String artifactId,
            String packaging,
            Path moduleDirectory,
            boolean hasModules,
            FileVaultPluginConfig fileVaultConfig,
            boolean hasValidOsgiConfigs,
            boolean hasBndPlugin,
            boolean hasFilters,
            boolean dependsOnAemSdk) {
    }
}
