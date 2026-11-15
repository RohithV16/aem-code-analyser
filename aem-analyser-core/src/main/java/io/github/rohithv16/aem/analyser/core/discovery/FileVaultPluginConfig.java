package io.github.rohithv16.aem.analyser.core.discovery;

import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extracted configuration of the Jackrabbit FileVault package Maven plugin for a module POM.
 */
public record FileVaultPluginConfig(String artifactId, boolean hasEmbeddeds, List<String> embeddedTargets,
                                    String packageType) {

    public static FileVaultPluginConfig fromPluginConfiguration(String artifactId, Object configuration) {
        if (!(configuration instanceof Xpp3Dom dom)) {
            return new FileVaultPluginConfig(artifactId, false, List.of(), null);
        }
        boolean hasEmbeddeds = false;
        List<String> targets = new ArrayList<>();
        Xpp3Dom embeddeds = dom.getChild("embeddeds");
        if (embeddeds != null && embeddeds.getChildCount() > 0) {
            hasEmbeddeds = true;
            for (Xpp3Dom embedded : embeddeds.getChildren("embedded")) {
                Xpp3Dom target = embedded.getChild("target");
                if (target != null && target.getValue() != null) {
                    targets.add(target.getValue().strip());
                }
            }
        }
        Xpp3Dom type = dom.getChild("packageType");
        return new FileVaultPluginConfig(artifactId, hasEmbeddeds, targets, type == null ? null : type.getValue());
    }

    public Optional<String> getPackageType() {
        return Optional.ofNullable(packageType);
    }
}
