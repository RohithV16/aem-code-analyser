package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.discovery.FileVaultPluginConfig;
import io.github.rohithv16.aem.analyser.core.discovery.FileVaultPluginConfig.Embedded;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleClassification;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.config.PackageEmbedGraphParser;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AEM-CLOUD-009: deployable modules the {@code all} container never installs.
 *
 * <p>AEM deploys one package per application, and that package - the container, usually
 * {@code all} - "must not have any content or code of its own"; it exists to embed and install the
 * modules that do. Two failures are silent by construction: a new module that is added to the
 * reactor but not to the container's {@code <embeddeds>} builds and passes the pipeline while its
 * content simply is not in the environment, and a bundle embedded outside an {@code .../install}
 * path is carried in the package but never started. Both are discovered weeks later, by an author
 * wondering why a component or a dialog is missing.
 *
 * <p>Reachability follows what Cloud Manager actually installs: the container's
 * {@code <embeddeds>}, plus each reached module's {@code <repositoryStructurePackages>} (structure
 * packages are never embedded, they only describe filter roots) and its package-level
 * {@code <dependencies>}. Modules that opt out with {@code cloudManagerTarget=none} are not
 * expected to be reachable, and a reactor without a container is left alone: many projects ship a
 * single package, and nothing there can be "unreachable".
 */
public final class AemCloud009Rule implements RuleEvaluator {

    /** Classifications that represent something deployed into the repository or the OSGi container. */
    private static final Set<ModuleClassification> DEPLOYABLE = EnumSet.of(
            ModuleClassification.APPS_MODULE,
            ModuleClassification.CONTENT_MODULE,
            ModuleClassification.STRUCTURE_MODULE,
            ModuleClassification.ACL_MODULE,
            ModuleClassification.CONFIG_MODULE,
            ModuleClassification.BUNDLE_MODULE);

    private final PackageEmbedGraphParser parser = new PackageEmbedGraphParser();

    @Override
    public String getRuleId() {
        return RuleId.AEM_CLOUD_009.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<ModuleDescriptor> modules = context.modules();
        if (modules.isEmpty()) {
            return List.of();
        }
        Map<String, ModuleDescriptor> byArtifactId = new LinkedHashMap<>();
        for (ModuleDescriptor module : modules) {
            byArtifactId.putIfAbsent(module.artifactId(), module);
        }
        List<ModuleDescriptor> containers = modules.stream()
                .filter(module -> module.classification() == ModuleClassification.CONTAINER_MODULE)
                .toList();
        if (containers.isEmpty()) {
            return List.of();
        }

        Map<String, FileVaultPluginConfig> configs = parser.scan(modules);
        Set<String> reachable = reachableFrom(containers, configs);

        List<Violation> violations = new ArrayList<>();
        violations.addAll(containerFindings(context, containers, configs, byArtifactId));
        violations.addAll(unreachableFindings(context, modules, configs, containers, reachable));
        return violations;
    }

    /** Modules the container installs, directly or through a structure package / package dependency. */
    private Set<String> reachableFrom(List<ModuleDescriptor> containers,
                                      Map<String, FileVaultPluginConfig> configs) {
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (ModuleDescriptor container : containers) {
            queue.add(container.artifactId());
        }
        while (!queue.isEmpty()) {
            String artifactId = queue.poll();
            if (!reachable.add(artifactId)) {
                continue;
            }
            FileVaultPluginConfig config = configs.get(artifactId);
            if (config == null) {
                continue;
            }
            for (Embedded embedded : config.embeddeds()) {
                if (embedded.artifactId() != null) {
                    queue.add(embedded.artifactId());
                }
            }
            queue.addAll(config.repositoryStructurePackages());
            queue.addAll(config.packageDependencies());
        }
        return reachable;
    }

    private List<Violation> containerFindings(RuleContext context,
                                              List<ModuleDescriptor> containers,
                                              Map<String, FileVaultPluginConfig> configs,
                                              Map<String, ModuleDescriptor> byArtifactId) {
        List<Violation> violations = new ArrayList<>();
        for (ModuleDescriptor container : containers) {
            FileVaultPluginConfig config = configs.get(container.artifactId());
            if (config != null) {
                violations.addAll(bundleInstallFindings(context, container, config, byArtifactId));
            }
            if (shipsOwnContent(context, container)) {
                violations.add(new Violation(
                        RuleId.AEM_CLOUD_009.id(),
                        Severity.ERROR,
                        "CONTAINER_SHIPPING_CONTENT",
                        "Container module '" + container.artifactId() + "' carries its own jcr_root. "
                                + "The 'all' package must not have content of its own; only the modules "
                                + "it embeds are installed.",
                        context.relativePath(container.pomPath()),
                        TextRegion.forLine(parser.lineOf(container.pomPath(), "<artifactId>"
                                + container.artifactId() + "</artifactId>")),
                        container.artifactId(),
                        "Move the content into the module that owns it (ui.apps, ui.content, ui.config) "
                                + "and leave the container with <embeddeds> only.",
                        List.of()));
            }
        }
        return violations;
    }

    private List<Violation> bundleInstallFindings(RuleContext context,
                                                  ModuleDescriptor container,
                                                  FileVaultPluginConfig config,
                                                  Map<String, ModuleDescriptor> byArtifactId) {
        List<Violation> violations = new ArrayList<>();
        for (Embedded embedded : config.embeddeds()) {
            ModuleDescriptor target = embedded.artifactId() == null
                    ? null
                    : byArtifactId.get(embedded.artifactId());
            boolean bundle = target != null
                    && (target.classification() == ModuleClassification.BUNDLE_MODULE
                    || "bundle".equalsIgnoreCase(target.packaging()));
            if (!bundle || embedded.installsIntoInstallPath()) {
                continue;
            }
            violations.add(new Violation(
                    RuleId.AEM_CLOUD_009.id(),
                    Severity.ERROR,
                    "BUNDLE_EMBEDDED_WITHOUT_INSTALL",
                    "Bundle '" + embedded.artifactId() + "' is embedded with target '"
                            + (embedded.target() == null ? "(none)" : embedded.target())
                            + "', which is not an .../install path, so the package installer never "
                            + "installs or starts the bundle.",
                    context.relativePath(container.pomPath()),
                    TextRegion.forLine(parser.lineOf(container.pomPath(),
                            "<artifactId>" + embedded.artifactId() + "</artifactId>")),
                    embedded.artifactId(),
                    "Embed the bundle under an install path such as "
                            + "/apps/<project>-packages/application/install.",
                    List.of()));
        }
        return violations;
    }

    private List<Violation> unreachableFindings(RuleContext context,
                                                List<ModuleDescriptor> modules,
                                                Map<String, FileVaultPluginConfig> configs,
                                                List<ModuleDescriptor> containers,
                                                Set<String> reachable) {
        ModuleDescriptor container = containers.get(0);
        List<Violation> violations = new ArrayList<>();
        for (ModuleDescriptor module : modules) {
            if (!DEPLOYABLE.contains(module.classification()) || reachable.contains(module.artifactId())) {
                continue;
            }
            FileVaultPluginConfig config = configs.get(module.artifactId());
            if (config != null && config.targetsNoCloudManager()) {
                continue;
            }
            violations.add(new Violation(
                    RuleId.AEM_CLOUD_009.id(),
                    RuleId.AEM_CLOUD_009.defaultLevel(),
                    "UNREACHABLE_MODULE",
                    "Module '" + module.artifactId() + "' (" + module.classification()
                            + ") is part of the reactor but the '" + container.artifactId()
                            + "' container neither embeds it nor reaches it through a repository "
                            + "structure package or package dependency, so its content is never "
                            + "installed in AEM as a Cloud Service.",
                    context.relativePath(module.pomPath()),
                    TextRegion.forLine(parser.lineOf(module.pomPath(),
                            "<artifactId>" + module.artifactId() + "</artifactId>")),
                    module.artifactId(),
                    "Add the module to the container's <embeddeds> with an install target, or delete "
                            + "it from the reactor if it is no longer deployed.",
                    List.of(new RelatedLocation(
                            "Container package that must embed it",
                            context.relativePath(container.pomPath()),
                            TextRegion.forLine(parser.lineOf(container.pomPath(),
                                    "<artifactId>" + container.artifactId() + "</artifactId>"))))));
        }
        return violations;
    }

    /**
     * True when the container ships a {@code jcr_root} with at least one file below it. An empty
     * directory tree or a package that only holds {@code META-INF/vault} stays silent.
     */
    private boolean shipsOwnContent(RuleContext context, ModuleDescriptor container) {
        Path jcrRoot = container.moduleDirectory().resolve("src/main/content/jcr_root");
        return !context.fileInventory().filesUnder(jcrRoot).isEmpty();
    }
}
