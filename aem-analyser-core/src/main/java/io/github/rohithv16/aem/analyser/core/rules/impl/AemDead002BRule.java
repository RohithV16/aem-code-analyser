package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.EdgeType;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiOcdNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OcdPropertyMethodNode;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * FEAT-RULE-04: Rule AEM-DEAD-002B — Unconsumed OCD Properties.
 * Flags {@code @AttributeDefinition} methods never accessed in Java, aggregating call sites
 * across ALL designating components via {@code CONSUMES_PROPERTY} in-degree (O(1) per method).
 */
public final class AemDead002BRule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_DEAD_002B.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (OSGiOcdNode ocd : context.index().getOcdLookup().values()) {
            List<OSGiComponentNode> designatingComponents = context.components().stream()
                    .filter(c -> c.getDesignatedOcdFqn().map(ocd.getOcdFqn()::equals).orElse(false))
                    .toList();
            if (designatingComponents.isEmpty()) {
                continue; // no consumer possible; ghost OCDs belong to AEM-DEAD-002A
            }
            for (OcdPropertyMethodNode method : ocd.getPropertyMethods().values()) {
                if (io.github.rohithv16.aem.analyser.core.rules.catalogs.FrameworkPropertyWhitelist.isWhitelisted(method.getNormalizedOsgiPropName())
                        || io.github.rohithv16.aem.analyser.core.rules.catalogs.FrameworkPropertyWhitelist.isWhitelisted(method.getMethodName())) {
                    continue;
                }
                if (context.graph().inDegree(method, EdgeType.CONSUMES_PROPERTY) == 0) {
                    List<RelatedLocation> related = new ArrayList<>();
                    var location = method.getSourceLocation();
                    TextRegion region = location == null ? TextRegion.forWholeFile()
                            : new TextRegion(location.line(), location.column(),
                            location.line(), location.column() + method.getMethodName().length());
                    designatingComponents.stream()
                            .filter(c -> c.getFilePath() != null)
                            .findFirst()
                            .ifPresent(c -> related.add(new RelatedLocation(
                                    "Designating component '" + c.getComponentClassFqn() + "'.",
                                    relativePath(context, c.getFilePath()),
                                    TextRegion.forWholeFile())));
                    violations.add(new Violation(
                            RuleId.AEM_DEAD_002B.id(),
                            RuleId.AEM_DEAD_002B.defaultLevel(),
                            "UNCONSUMED_OCD_PROPERTY",
                            "Unconsumed OCD property: @AttributeDefinition method '"
                                    + method.getMethodName() + "' on '" + ocd.getOcdFqn()
                                    + "' is never invoked by any of the "
                                    + designatingComponents.size() + " designating component(s).",
                            relativePath(context, method.getFilePath()),
                            region,
                            ocd.getPid(),
                            "Delete the unused attribute definition, or consume it from the designating"
                                    + " component (config." + method.getMethodName() + "()).",
                            related));
                }
            }
        }
        return violations;
    }

    private String relativePath(RuleContext context, java.nio.file.Path path) {
        return context.relativePath(path);
    }
}
