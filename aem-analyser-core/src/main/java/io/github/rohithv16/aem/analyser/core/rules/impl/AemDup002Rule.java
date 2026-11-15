package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Rule AEM-DUP-002 — Duplicate / Conflicting OSGi Configurations.
 *
 * <p>Detects when multiple configuration files in {@code ui.config} target the exact same PID
 * or Factory PID with identical subname in the same runmode context.
 * In AEM, this leads to deployment race conditions where one file overwrites the other.
 */
public final class AemDup002Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_DUP_002.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        // TreeMap keeps violation output stable across runs; a HashMap made the primary/related
        // location of a duplicate pair depend on filesystem discovery order.
        Map<String, List<OSGiConfigNode>> groupedConfigs = new TreeMap<>();

        for (OSGiConfigNode config : context.configs()) {
            String runmodeKey = String.join(".", new TreeSet<>(config.getRunmodes()));
            String subname = config.getFactorySubname() == null ? "" : config.getFactorySubname();
            String key = runmodeKey + "::" + config.getPid() + "::" + subname;
            groupedConfigs.computeIfAbsent(key, k -> new ArrayList<>()).add(config);
        }

        for (Map.Entry<String, List<OSGiConfigNode>> entry : groupedConfigs.entrySet()) {
            if (entry.getValue().size() > 1) {
                // Deterministic ordering: the lexicographically first file is always the "first
                // definition", regardless of the order discovery supplied the nodes.
                List<OSGiConfigNode> duplicates = new ArrayList<>(entry.getValue());
                duplicates.sort(Comparator.comparing(c -> relativePath(context, c.getFilePath())));
                OSGiConfigNode first = duplicates.get(0);
                for (int i = 1; i < duplicates.size(); i++) {
                    OSGiConfigNode duplicate = duplicates.get(i);
                    String runmodes = first.getRunmodes().isEmpty() ? "default (all)" : String.join(", ", first.getRunmodes());

                    List<RelatedLocation> related = List.of(new RelatedLocation(
                            "First definition: " + relativePath(context, first.getFilePath()),
                            relativePath(context, first.getFilePath()),
                            TextRegion.forWholeFile()
                    ));

                    violations.add(new Violation(
                            RuleId.AEM_DUP_002.id(),
                            RuleId.AEM_DUP_002.defaultLevel(),
                            "DUPLICATE_OSGI_CONFIG",
                            String.format("Duplicate OSGi configuration targeting PID '%s' in runmode [%s]. Conflicting file: %s",
                                    duplicate.getPid(), runmodes, relativePath(context, first.getFilePath())),
                            relativePath(context, duplicate.getFilePath()),
                            TextRegion.forWholeFile(),
                            duplicate.getPid(),
                            "Merge configuration properties into a single file or differentiate factory identifiers.",
                            related
                    ));
                }
            }
        }

        return violations;
    }

    private String relativePath(RuleContext context, Path path) {
        if (path == null) {
            return "";
        }
        try {
            return context.projectRoot().relativize(path).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }
}
