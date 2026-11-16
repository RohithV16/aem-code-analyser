package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleClassification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Deterministic 11-category semantic module classifier (spec §5.3.1 decision tree).
 */
public final class ModuleClassifier {

    public ModuleClassification classify(ClassificationInput input) {
        String packaging = input.packaging() == null
                ? "jar"
                : input.packaging().toLowerCase(Locale.ROOT);
        String artifactId = input.artifactId() == null
                ? ""
                : input.artifactId().toLowerCase(Locale.ROOT);
        String pathText = input.moduleDirectory() == null
                ? ""
                : input.moduleDirectory().toString().toLowerCase(Locale.ROOT);
        boolean mentionsDispatcher = artifactId.contains("dispatcher") || pathText.contains("dispatcher");
        boolean mentionsPipeline = artifactId.contains("pipeline") || pathText.contains("pipeline");

        return switch (packaging) {
            case "pom" -> classifyPom(input, artifactId, mentionsDispatcher, mentionsPipeline);
            case "content-package" -> classifyContentPackage(input, artifactId);
            case "jar", "bundle" -> classifyJarOrBundle(input, artifactId, packaging);
            default -> classifyByNaming(artifactId, mentionsDispatcher, mentionsPipeline);
        };
    }

    /** {@code pom} packaging: aggregators, frontends, dispatcher/pipeline carriers, parent POMs. */
    private ModuleClassification classifyPom(ClassificationInput input, String artifactId,
            boolean mentionsDispatcher, boolean mentionsPipeline) {
        if (input.hasModules()) {
            return ModuleClassification.AGGREGATOR_MODULE;
        }
        if (hasFrontendBuild(input.moduleDirectory())) {
            return ModuleClassification.FRONTEND_MODULE;
        }
        if (mentionsDispatcher) {
            return ModuleClassification.DISPATCHER_MODULE;
        }
        if (mentionsPipeline) {
            return ModuleClassification.PIPELINE_MODULE;
        }
        if (isTestModule(artifactId) && !input.hasBndPlugin()) {
            return ModuleClassification.TEST_MODULE;
        }
        if (artifactId.endsWith("-parent") || artifactId.endsWith(".parent")) {
            return ModuleClassification.AGGREGATOR_MODULE;
        }
        return ModuleClassification.PIPELINE_MODULE;
    }

    /** {@code jar}/{@code bundle} packaging: frontend, test, or a real OSGi bundle. */
    private ModuleClassification classifyJarOrBundle(ClassificationInput input, String artifactId,
            String packaging) {
        if (hasFrontendBuild(input.moduleDirectory())) {
            return ModuleClassification.FRONTEND_MODULE;
        }
        if (isTestModule(artifactId) && !input.hasBndPlugin()) {
            return ModuleClassification.TEST_MODULE;
        }
        if (packaging.equals("bundle") || input.hasBndPlugin() || input.dependsOnAemSdk()) {
            return ModuleClassification.BUNDLE_MODULE;
        }
        // A plain jar without bnd/AEM SDK is not an OSGi bundle. Do not pretend it is.
        return ModuleClassification.UNCLASSIFIED;
    }

    /** Any other packaging: decided purely by artifact name and module path. */
    private ModuleClassification classifyByNaming(String artifactId, boolean mentionsDispatcher,
            boolean mentionsPipeline) {
        if (mentionsDispatcher) {
            return ModuleClassification.DISPATCHER_MODULE;
        }
        if (mentionsPipeline) {
            return ModuleClassification.PIPELINE_MODULE;
        }
        if (isTestModule(artifactId)) {
            return ModuleClassification.TEST_MODULE;
        }
        return ModuleClassification.UNCLASSIFIED;
    }

    private ModuleClassification classifyContentPackage(ClassificationInput input, String artifactId) {
        ModuleClassification structural = classifyContentStructure(input);
        if (structural != null) {
            return structural;
        }
        ModuleClassification named = classifyContentNaming(artifactId);
        if (named != null) {
            return named;
        }
        ModuleClassification declared = classifyByPackageType(input.fileVaultConfig());
        if (declared != null) {
            return declared;
        }
        ModuleClassification physical = classifyContentTree(input);
        return physical != null ? physical : ModuleClassification.UNCLASSIFIED;
    }

    /** File evidence from the package itself; {@code null} when it does not decide the category. */
    private ModuleClassification classifyContentStructure(ClassificationInput input) {
        // Condition 1: embeddeds are the definitive signature of the 'all' container
        if (input.fileVaultConfig() != null && input.fileVaultConfig().hasEmbeddeds()) {
            return ModuleClassification.CONTAINER_MODULE;
        }
        // Condition 1b: conf-only content package (ACL / context-aware config, no apps tree)
        if (hasJcrSubdirectory(input.moduleDirectory(), "conf")
                && !hasJcrSubdirectory(input.moduleDirectory(), "apps")) {
            return ModuleClassification.ACL_MODULE;
        }
        // Condition 2: physical OSGi config files under strict osgiconfig/ anchoring identify ui.config
        if (input.hasValidOsgiConfigs()) {
            return ModuleClassification.CONFIG_MODULE;
        }
        return null;
    }

    /** Condition 3: naming conventions when the project has not been built. */
    private ModuleClassification classifyContentNaming(String artifactId) {
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
        return null;
    }

    /** The {@code packageType} the FileVault plugin declares; an unknown type stays unclassified. */
    private ModuleClassification classifyByPackageType(FileVaultPluginConfig fileVaultConfig) {
        String packageType = fileVaultConfig == null ? null : fileVaultConfig.packageType();
        if (packageType == null) {
            return null;
        }
        return switch (packageType.toLowerCase(Locale.ROOT)) {
            case "application" -> ModuleClassification.APPS_MODULE;
            case "mixed" -> ModuleClassification.ACL_MODULE;
            case "content" -> ModuleClassification.CONTENT_MODULE;
            case "container" -> ModuleClassification.CONTAINER_MODULE;
            default -> ModuleClassification.UNCLASSIFIED;
        };
    }

    /** Fallback: physical jcr_root content types. */
    private ModuleClassification classifyContentTree(ClassificationInput input) {
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
        return null;
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
