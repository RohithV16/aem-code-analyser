package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AEM-CLOUD-005: OSGi configuration placed in an unrecognised runmode folder.
 */
public final class AemCloud005Rule implements RuleEvaluator {

    private static final Set<String> VALID_RUNMODES = Set.of(
            "author", "publish", "dev", "prod", "stage", "local", "rde", "test",
            "integration", "preview", "cloud", "development");

    @Override
    public String getRuleId() {
        return RuleId.AEM_CLOUD_005.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (OSGiConfigNode config : context.configs()) {
            Path file = config.getFilePath();
            if (file == null) {
                continue;
            }
            String folder = runmodeFolder(file);
            if (folder == null || folder.equals("config")) {
                continue;
            }
            String suffix = folder.substring("config".length());
            if (suffix.isBlank()) {
                continue;
            }
            for (String runmode : suffix.split("\\.")) {
                if (runmode.isBlank()) {
                    continue;
                }
                if (!VALID_RUNMODES.contains(runmode.toLowerCase(Locale.ROOT))) {
                    violations.add(new Violation(
                            RuleId.AEM_CLOUD_005.id(),
                            RuleId.AEM_CLOUD_005.defaultLevel(),
                            "INVALID_RUNMODE_FOLDER",
                            "OSGi configuration folder '" + folder + "' uses unrecognised runmode '"
                                    + runmode + "'.",
                            relativePath(context, file),
                            TextRegion.forWholeFile(),
                            config.getPid(),
                            "Move the configuration to a valid runmode folder (config, config.author,"
                                    + " config.publish, config.dev, config.stage, config.prod, ...).",
                            List.of()));
                    break;
                }
            }
        }
        return violations;
    }

    private String runmodeFolder(Path file) {
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return null;
        }
        String name = parent.getFileName().toString();
        return name.startsWith("config") ? name : null;
    }

    private String relativePath(RuleContext context, Path path) {
        return context.projectRoot().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
