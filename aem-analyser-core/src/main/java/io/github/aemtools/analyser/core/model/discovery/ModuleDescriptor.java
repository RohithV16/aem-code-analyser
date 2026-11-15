package io.github.aemtools.analyser.core.model.discovery;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * A discovered Maven module (or non-Maven cloud config descriptor) in the reactor tree.
 *
 * @param artifactId        Maven artifact id
 * @param groupId           Maven group id
 * @param packaging         Maven packaging (pom, jar, bundle, content-package, ...)
 * @param moduleDirectory   absolute module directory
 * @param pomPath           absolute pom.xml path (null for non-Maven descriptors)
 * @param classification    semantic classification
 * @param parentArtifactId  parent artifact id, if any
 * @param hasModules        whether the POM declares child modules
 * @param osgiConfigPaths   strict-anchored OSGi configuration files inside this module
 * @param exportedPackages  bundle Export-Package patterns declared by this module
 */
public record ModuleDescriptor(
        String artifactId,
        String groupId,
        String packaging,
        Path moduleDirectory,
        Path pomPath,
        ModuleClassification classification,
        String parentArtifactId,
        boolean hasModules,
        Set<Path> osgiConfigPaths,
        Set<String> exportedPackages) {

    public ModuleDescriptor {
        osgiConfigPaths = Set.copyOf(osgiConfigPaths);
        exportedPackages = Set.copyOf(exportedPackages);
    }

    public boolean is(ClassificationGroup group) {
        return group.matches(classification);
    }

    public enum ClassificationGroup {
        AGGREGATORS(List.of(ModuleClassification.AGGREGATOR_MODULE)),
        BUNDLES(List.of(ModuleClassification.BUNDLE_MODULE)),
        CONFIGS(List.of(ModuleClassification.CONFIG_MODULE)),
        CONTENT(List.of(ModuleClassification.CONTAINER_MODULE, ModuleClassification.APPS_MODULE,
                ModuleClassification.CONTENT_MODULE, ModuleClassification.STRUCTURE_MODULE,
                ModuleClassification.ACL_MODULE)),
        ALL(List.of());

        private final List<ModuleClassification> values;

        ClassificationGroup(List<ModuleClassification> values) {
            this.values = values;
        }

        boolean matches(ModuleClassification classification) {
            return values.isEmpty() || values.contains(classification);
        }
    }
}
