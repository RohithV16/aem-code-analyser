package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.EdgeType;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.ConfigPropertyNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiOcdNode;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;
import io.github.rohithv16.aem.analyser.core.rules.catalogs.FrameworkPropertyWhitelist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FEAT-RULE-03: Rule AEM-DEAD-002A — Ghost Configuration Properties.
 * Flags properties in a bound configuration file that have no matching
 * {@code @AttributeDefinition} in the designated OCD and are not whitelisted framework keys.
 *
 * <p>OSGi property names are case-sensitive and are compared verbatim against the Metatype 1.4
 * normalized property name (which already honours an explicit {@code @AttributeDefinition(id=...)}).
 * A config bound to several components is valid when <em>any</em> of their OCDs declares the key;
 * configs bound to a component without a resolvable OCD are skipped because their properties
 * cannot be validated safely.
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

            Map<String, OSGiOcdNode> ocds = new LinkedHashMap<>();
            boolean unresolvedComponent = false;
            for (OSGiComponentNode component : components) {
                OSGiOcdNode ocd = component.getDesignatedOcdFqn()
                        .map(fqn -> context.index().findOcdByFqn(fqn))
                        .orElse(null);
                if (ocd == null) {
                    unresolvedComponent = true;
                } else {
                    ocds.putIfAbsent(ocd.getOcdFqn(), ocd);
                }
            }
            if (ocds.isEmpty() || unresolvedComponent) {
                continue; // no complete metatype contract to validate against
            }

            for (ConfigPropertyNode property : config.getProperties().values()) {
                String rawKey = property.getKey();
                if (rawKey == null) {
                    continue;
                }
                // OSGi keys are exact. A key written in the Java method-name style
                // (webconsole_configurationFactory_nameHint) is accepted as a compatibility alias
                // for its Metatype-normalized form; this is a suppression, never a ghost report.
                String methodStyleKey = rawKey.indexOf('_') >= 0
                        ? io.github.rohithv16.aem.analyser.core.graph.MetatypeNameNormalizer
                                .normalizeMethodName(rawKey, null)
                        : null;
                if (FrameworkPropertyWhitelist.isWhitelisted(rawKey)
                        || (methodStyleKey != null && FrameworkPropertyWhitelist.isWhitelisted(methodStyleKey))) {
                    continue;
                }
                boolean declared = ocds.values().stream()
                        .anyMatch(ocd -> declares(ocd, rawKey)
                                || (methodStyleKey != null && declares(ocd, methodStyleKey)));
                if (declared) {
                    continue;
                }
                var location = property.getSourceLocation();
                TextRegion region = location == null ? TextRegion.forWholeFile()
                        : new TextRegion(location.line(), location.column(),
                        location.line(), location.column() + property.getRawKey().length());
                List<RelatedLocation> related = new ArrayList<>();
                for (OSGiOcdNode ocd : ocds.values()) {
                    if (ocd.getFilePath() != null) {
                        related.add(new RelatedLocation(
                                "Bound @ObjectClassDefinition '" + ocd.getOcdFqn() + "' defines no such attribute.",
                                relativePath(context, ocd.getFilePath()), regionOf(ocd)));
                    }
                }
                violations.add(new Violation(
                        RuleId.AEM_DEAD_002A.id(),
                        RuleId.AEM_DEAD_002A.defaultLevel(),
                        "GHOST_CONFIGURATION_PROPERTY",
                        "Ghost configuration property '" + rawKey + "' in '"
                                + fileName(config) + "' has no corresponding @AttributeDefinition in "
                                + describe(ocds) + ".",
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

    private boolean declares(OSGiOcdNode ocd, String key) {
        return ocd.getPropertyMethods().values().stream()
                .map(m -> m.getNormalizedOsgiPropName())
                .anyMatch(key::equals);
    }

    private String describe(Map<String, OSGiOcdNode> ocds) {
        if (ocds.size() == 1) {
            return "'" + ocds.keySet().iterator().next() + "'";
        }
        return ocds.keySet().stream().map(fqn -> "'" + fqn + "'").toList().toString();
    }

    private String fileName(OSGiConfigNode config) {
        var path = config.getFilePath();
        return path == null ? "" : path.getFileName().toString();
    }

    private String relativePath(RuleContext context, java.nio.file.Path path) {
        return context.relativePath(path);
    }

    private TextRegion regionOf(io.github.rohithv16.aem.analyser.core.model.graph.node.AbstractPolyglotNode node) {
        var location = node.getSourceLocation();
        if (location == null) {
            return TextRegion.forWholeFile();
        }
        return new TextRegion(location.line(), location.column(), location.line(), location.column() + 12);
    }
}
