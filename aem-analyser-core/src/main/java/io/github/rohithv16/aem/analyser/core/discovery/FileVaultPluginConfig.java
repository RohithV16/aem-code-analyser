package io.github.rohithv16.aem.analyser.core.discovery;

import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extracted configuration of the Jackrabbit FileVault package Maven plugin for a module POM.
 *
 * <p>Everything the plugin decides about <em>packaging</em> is kept, not only the target strings:
 * the embed graph (which artifact is embedded where), the repository structure packages a package
 * depends on for its filter roots, and the package-level dependencies it embeds. AEM-CLOUD-009
 * needs the artifact ids to walk that graph; exposing the targets alone made an unreachable module
 * indistinguishable from a module that is embedded without an install path.
 *
 * @param artifactId                  the module's own Maven artifact id
 * @param embeddeds                   {@code <embeddeds>/<embedded>} entries, in declaration order
 * @param repositoryStructurePackages artifact ids under {@code <repositoryStructurePackages>}
 * @param packageDependencies         artifact ids under the plugin's {@code <dependencies>}
 * @param packageType                 {@code <packageType>}, or {@code null} when not declared
 * @param cloudManagerTarget          {@code <properties>/<cloudManagerTarget>}, or {@code null}
 */
public record FileVaultPluginConfig(String artifactId,
                                    List<Embedded> embeddeds,
                                    List<String> repositoryStructurePackages,
                                    List<String> packageDependencies,
                                    String packageType,
                                    String cloudManagerTarget) {

    /** One {@code <embedded>} entry: the artifact and the repository path it is installed into. */
    public record Embedded(String artifactId, String groupId, String type, String target) {

        /**
         * True when the target is an install path: the package installer only installs a bundle
         * from {@code .../install} (or a subdirectory of it), so an embedded jar anywhere else is
         * carried in the container and never started.
         */
        public boolean installsIntoInstallPath() {
            if (target == null) {
                return false;
            }
            String path = target.strip();
            return path.endsWith("/install") || path.contains("/install/") || "/install".equals(path);
        }
    }

    public FileVaultPluginConfig {
        embeddeds = List.copyOf(embeddeds == null ? List.of() : embeddeds);
        repositoryStructurePackages =
                List.copyOf(repositoryStructurePackages == null ? List.of() : repositoryStructurePackages);
        packageDependencies = List.copyOf(packageDependencies == null ? List.of() : packageDependencies);
    }

    public boolean hasEmbeddeds() {
        return !embeddeds.isEmpty();
    }

    /** Targets of every embedded artifact, for consumers that only care where things land. */
    public List<String> embeddedTargets() {
        List<String> targets = new ArrayList<>(embeddeds.size());
        for (Embedded embedded : embeddeds) {
            if (embedded.target() != null) {
                targets.add(embedded.target());
            }
        }
        return List.copyOf(targets);
    }

    public Optional<String> getPackageType() {
        return Optional.ofNullable(packageType);
    }

    /** True when the module opts out of Cloud Manager's package set ({@code cloudManagerTarget=none}). */
    public boolean targetsNoCloudManager() {
        return cloudManagerTarget != null && "none".equalsIgnoreCase(cloudManagerTarget.strip());
    }

    public static FileVaultPluginConfig fromPluginConfiguration(String artifactId, Object configuration) {
        if (!(configuration instanceof Xpp3Dom dom)) {
            return empty(artifactId);
        }
        List<Embedded> embeddeds = new ArrayList<>();
        Xpp3Dom embeddedsDom = dom.getChild("embeddeds");
        if (embeddedsDom != null && embeddedsDom.getChildCount() > 0) {
            for (Xpp3Dom embedded : embeddedsDom.getChildren("embedded")) {
                embeddeds.add(new Embedded(
                        valueOf(embedded, "artifactId"),
                        valueOf(embedded, "groupId"),
                        valueOf(embedded, "type"),
                        valueOf(embedded, "target")));
            }
        }
        Xpp3Dom structurePackages = dom.getChild("repositoryStructurePackages");
        List<String> repositoryStructurePackages = new ArrayList<>();
        if (structurePackages != null) {
            for (Xpp3Dom structure : structurePackages.getChildren("repositoryStructurePackage")) {
                String id = valueOf(structure, "artifactId");
                if (id != null) {
                    repositoryStructurePackages.add(id);
                }
            }
        }
        List<String> packageDependencies = new ArrayList<>();
        Xpp3Dom dependencies = dom.getChild("dependencies");
        if (dependencies != null) {
            for (Xpp3Dom dependency : dependencies.getChildren("dependency")) {
                String id = valueOf(dependency, "artifactId");
                if (id != null) {
                    packageDependencies.add(id);
                }
            }
        }
        Xpp3Dom properties = dom.getChild("properties");
        String cloudManagerTarget = properties == null ? null : valueOf(properties, "cloudManagerTarget");
        return new FileVaultPluginConfig(artifactId, embeddeds, repositoryStructurePackages,
                packageDependencies, valueOf(dom, "packageType"), cloudManagerTarget);
    }

    private static FileVaultPluginConfig empty(String artifactId) {
        return new FileVaultPluginConfig(artifactId, List.of(), List.of(), List.of(), null, null);
    }

    private static String valueOf(Xpp3Dom parent, String child) {
        Xpp3Dom node = parent.getChild(child);
        if (node == null || node.getValue() == null || node.getValue().isBlank()) {
            return null;
        }
        return node.getValue().strip();
    }
}
