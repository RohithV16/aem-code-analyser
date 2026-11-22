package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rule AEM-QUAL-008 — a mandatory {@code @Reference} that no component provides.
 *
 * <p>Declarative Services only activates a component once every mandatory reference is satisfied.
 * When the target type is declared by the project but no {@code @Component} registers it, javac is
 * happy (the type exists) and the OSGi resolver is happy (the package is imported), yet the
 * component never becomes active: the servlet 404s, the job never runs, the scheduler stays silent.
 * The only clue is a {@code Declarative Services} log line at runtime.</p>
 *
 * <p>Scope guard: only targets declared by the analysed sources are judged, so references to
 * platform or dependency services are never reported. {@code OPTIONAL}, {@code MULTIPLE} and
 * {@code AT_LEAST_ONE} cardinalities are skipped because a missing provider is a valid state for
 * them.</p>
 */
public final class AemQual008Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_QUAL_008.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        Set<String> declaredTypes = declaredProjectTypes(context);
        if (declaredTypes.isEmpty()) {
            return violations;
        }
        Set<String> providers = providedServices(context);

        for (OSGiComponentNode component : context.components()) {
            for (Reference reference : referencesOf(component)) {
                if (!reference.cardinality().requiresProvider()) {
                    continue;
                }
                String target = reference.targetType();
                if (target == null || target.isBlank()) {
                    continue;
                }
                // Not declared by this project: a platform or dependency service we cannot judge.
                if (!declaredTypes.contains(target)) {
                    continue;
                }
                if (providers.contains(target)) {
                    continue;
                }
                violations.add(violation(context, component, reference));
            }
        }
        return violations;
    }

    private Violation violation(RuleContext context, OSGiComponentNode component, Reference reference) {
        int line = reference.location() != null ? reference.location().line()
                : (component.getSourceLocation() != null ? component.getSourceLocation().line() : 1);
        int column = reference.location() != null ? reference.location().column() : 1;
        Path file = reference.location() != null && reference.location().projectRelativePath() != null
                ? reference.location().projectRelativePath()
                : component.getFilePath();
        return new Violation(
                RuleId.AEM_QUAL_008.id(),
                RuleId.AEM_QUAL_008.defaultLevel(),
                "UNPROVIDED_MANDATORY_REFERENCE",
                "Component '" + component.getComponentClassFqn() + "' has a mandatory @Reference to '"
                        + reference.targetType() + "', but no component in the reactor registers that"
                        + " service. The reference can never be satisfied, so the component never activates.",
                context.relativePath(file),
                new TextRegion(line, Math.max(1, column), line, Math.max(1, column) + 12),
                null,
                "Register a @Component(service = " + reference.targetType() + ".class) implementation,"
                        + " or declare the @Reference optional if activation must not depend on it.",
                List.of());
    }

    /** A reference normalized from either the detailed list or the legacy name-to-type map. */
    private record Reference(String name, String targetType, OSGiComponentNode.ReferenceCardinality cardinality,
                             io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation location) {
    }

    private static List<Reference> referencesOf(OSGiComponentNode component) {
        List<Reference> references = new ArrayList<>();
        if (!component.getReferenceDetails().isEmpty()) {
            for (OSGiComponentNode.ServiceReference detail : component.getReferenceDetails()) {
                references.add(new Reference(detail.name(), detail.targetType(), detail.cardinality(),
                        detail.location()));
            }
            return references;
        }
        // Nodes built by older callers only carry the name-to-type map; treat those as mandatory,
        // which is the DS default.
        component.getReferences().forEach((name, target) -> references.add(
                new Reference(name, target, OSGiComponentNode.ReferenceCardinality.MANDATORY,
                        component.getSourceLocation())));
        return references;
    }

    /** Every service type some component in the reactor registers. */
    private static Set<String> providedServices(RuleContext context) {
        Set<String> providers = new LinkedHashSet<>();
        for (OSGiComponentNode component : context.components()) {
            providers.addAll(component.getServiceInterfaces());
        }
        if (context.dependencyIndex() != null) {
            providers.addAll(context.dependencyIndex().getServiceInterfaces());
        }
        return providers;
    }

    /**
     * Every type the analysed project declares, across all modules and buckets: components, plain
     * classes and interfaces, Sling Models and metatype interfaces.
     */
    private static Set<String> declaredProjectTypes(RuleContext context) {
        Set<String> types = new HashSet<>();
        if (context.buildResult() == null) {
            return types;
        }
        addKeys(types, context.buildResult().componentsByFqn());
        addKeys(types, context.buildResult().classNodes());
        addKeys(types, context.buildResult().modelNodes());
        if (context.graph() != null && context.graph().getIndex() != null) {
            types.addAll(context.graph().getIndex().getOcdLookup().keySet());
        }
        return types;
    }

    private static void addKeys(Set<String> target, Map<String, ?> nodes) {
        if (nodes != null) {
            target.addAll(nodes.keySet());
        }
    }
}
