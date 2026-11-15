package io.github.aemtools.analyser.core.rules.impl;

import io.github.aemtools.analyser.core.model.graph.EdgeType;
import io.github.aemtools.analyser.core.model.graph.TextRegion;
import io.github.aemtools.analyser.core.model.graph.node.ConfigPropertyNode;
import io.github.aemtools.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.aemtools.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.aemtools.analyser.core.model.graph.node.OSGiOcdNode;
import io.github.aemtools.analyser.core.model.report.RelatedLocation;
import io.github.aemtools.analyser.core.model.report.RuleId;
import io.github.aemtools.analyser.core.model.report.Violation;
import io.github.aemtools.analyser.core.rules.RuleContext;
import io.github.aemtools.analyser.core.rules.RuleEvaluator;
import io.github.aemtools.analyser.core.rules.catalogs.FrameworkPropertyWhitelist;

import java.util.ArrayList;
import java.util.List;

/**
 * FEAT-RULE-03: Rule AEM-DEAD-002A — Ghost Configuration Properties.
 * Flags properties in a bound configuration file that have no matching
 * {@code @AttributeDefinition} in the designated OCD and are not whitelisted framework keys.
 */
public final class AemDead002ARule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_DEAD_002A.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (OSGiConfigNode config : context.configs()) {
            if (config.getPid() == null) {
                continue;
            }
            List<OSGiComponentNode> components = context.graph().outgoingEdgesOfType(config, EdgeType.CONFIGURES).stream()
                    .map(e -> (OSGiComponentNode) e.getTarget())
                    .toList();
            if (components.isEmpty()) {
                continue; // orphaned configs are already covered by AEM-DEAD-001
            }
            OSGiComponentNode component = components.get(0);
            OSGiOcdNode ocd = component.getDesignatedOcdFqn()
                    .map(fqn -> context.index().findOcdByFqn(fqn))
                    .orElse(null);
            if (ocd == null) {
                continue; // no OCD to validate against; cannot verify property declarations
            }
            for (ConfigPropertyNode property : config.getProperties().values()) {
                String rawKey = property.getKey();
                String normalizedKey = io.github.aemtools.analyser.core.graph.MetatypeNameNormalizer
                        .normalizeMethodName(rawKey, null);
                if (FrameworkPropertyWhitelist.isWhitelisted(rawKey)
                        || (normalizedKey != null && FrameworkPropertyWhitelist.isWhitelisted(normalizedKey))) {
                    continue;
                }
                boolean declared = ocd.getPropertyMethods().values().stream()
                        .anyMatch(m -> m.getNormalizedOsgiPropName().equalsIgnoreCase(rawKey)
                                || (normalizedKey != null
                                        && m.getNormalizedOsgiPropName().equalsIgnoreCase(normalizedKey)));
                if (declared) {
                    continue;
                }
                var location = property.getSourceLocation();
                TextRegion region = location == null ? TextRegion.forWholeFile()
                        : new TextRegion(location.line(), location.column(),
                        location.line(), location.column() + property.getRawKey().length());
                List<RelatedLocation> related = new ArrayList<>();
                if (ocd.getFilePath() != null) {
                    related.add(new RelatedLocation(
                            "Bound @ObjectClassDefinition '" + ocd.getOcdFqn() + "' defines no such attribute.",
                            relativePath(context, ocd.getFilePath()), regionOf(ocd)));
                }
                violations.add(new Violation(
                        RuleId.AEM_DEAD_002A.id(),
                        RuleId.AEM_DEAD_002A.defaultLevel(),
                        "GHOST_CONFIGURATION_PROPERTY",
                        "Ghost configuration property '" + property.getKey() + "' in '"
                                + fileName(config) + "' has no corresponding @AttributeDefinition in '"
                                + ocd.getOcdFqn() + "'.",
                        relativePath(context, config.getFilePath()),
                        region,
                        config.getPid(),
                        "Remove the property, correct its spelling, or declare a matching"
                                + " @AttributeDefinition in the OCD interface.",
                        related));
            }
        }
        return violations;
    }

    private String fileName(OSGiConfigNode config) {
        var path = config.getFilePath();
        return path == null ? "" : path.getFileName().toString();
    }

    private String relativePath(RuleContext context, java.nio.file.Path path) {
        return context.projectRoot().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private TextRegion regionOf(io.github.aemtools.analyser.core.model.graph.node.AbstractPolyglotNode node) {
        var location = node.getSourceLocation();
        if (location == null) {
            return TextRegion.forWholeFile();
        }
        return new TextRegion(location.line(), location.column(), location.line(), location.column() + 12);
    }
}
