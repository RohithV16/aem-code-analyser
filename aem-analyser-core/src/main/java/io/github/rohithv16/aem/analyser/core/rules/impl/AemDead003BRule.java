package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.EdgeType;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.SlingModelNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.util.List;

/**
 * Rule AEM-DEAD-003B — Sling Models with no adaptation and no resource type binding.
 * Sling Models with 0 Java adaptations but a resourceType binding are retained as active
 * candidates (HTL cross-referencing arrives in Phase 2); models without a resourceType
 * and zero adaptations are reported at INFO severity.
 */
public final class AemDead003BRule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_DEAD_003B.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new java.util.ArrayList<>();

        // Collect all class names / FQNs referenced in HTL
        java.util.Set<String> htlReferencedClasses = new java.util.HashSet<>();
        if (context.htlUsages() != null) {
            for (var usage : context.htlUsages()) {
                if (usage.isJavaClass()) {
                    htlReferencedClasses.add(usage.javaClassName());
                    String simple = usage.javaClassName();
                    int lastDot = simple.lastIndexOf('.');
                    if (lastDot != -1) {
                        htlReferencedClasses.add(simple.substring(lastDot + 1));
                    }
                }
            }
        }

        // Collect all component resource types
        java.util.Set<String> componentResourceTypes = new java.util.HashSet<>();
        if (context.cqComponents() != null) {
            for (var comp : context.cqComponents()) {
                if (comp.resourceType() != null) {
                    componentResourceTypes.add(comp.resourceType());
                }
            }
        }

        for (SlingModelNode model : context.buildResult().modelNodes().values()) {
            // 0. Nested models are injection DTOs used by their enclosing model.
            if (model.isNested() || model.isExported()) {
                continue;
            }

            // 0b. Models consumed through Sling Models injection (@Self, @ChildResource, @Named),
            // as a superclass/interface, or as a return type of another model.
            if (referencedByAnotherModel(model, context.buildResult().modelNodes().values())) {
                continue;
            }

            // 1. Used if adapted in Java code
            if (context.graph().inDegree(model, EdgeType.ADAPTS_TO) > 0) {
                continue;
            }

            // 2. Used if referenced in HTL templates (by FQN, simple name, or adapter interface)
            String simpleName = model.getFqn().substring(model.getFqn().lastIndexOf('.') + 1);
            if (htlReferencedClasses.contains(model.getFqn()) || htlReferencedClasses.contains(simpleName)) {
                continue;
            }
            boolean adapterUsedInHtl = false;
            for (String adapter : model.getAdapters()) {
                if (htlReferencedClasses.contains(adapter)) {
                    adapterUsedInHtl = true;
                    break;
                }
                int lastDot = adapter.lastIndexOf('.');
                if (lastDot != -1 && htlReferencedClasses.contains(adapter.substring(lastDot + 1))) {
                    adapterUsedInHtl = true;
                    break;
                }
            }
            if (adapterUsedInHtl) {
                continue;
            }

            // 3. Used if bound to a declared JCR component resourceType
            if (model.getResourceType().isPresent()) {
                String modelResType = model.getResourceType().get();
                if (componentResourceTypes.isEmpty() || componentResourceTypes.contains(modelResType)) {
                    continue;
                }
            }

            var location = model.getSourceLocation();
            TextRegion region = location == null ? TextRegion.forWholeFile()
                    : new TextRegion(location.line(), location.column(),
                    location.line(), location.column() + 12);
            violations.add(new Violation(
                    RuleId.AEM_DEAD_003B.id(),
                    RuleId.AEM_DEAD_003B.defaultLevel(),
                    "UNUSED_SLING_MODEL",
                    "Sling Model '" + model.getFqn()
                            + "' has 0 Java adaptations, 0 HTL usages, no injection/superclass references"
                            + " from other models, and no active component resourceType binding.",
                    relativePath(context, model.getFilePath()),
                    region,
                    null,
                    "Remove the unused Sling Model or bind it to an active component, HTL template, or"
                            + " another model's injection point.",
                    List.of()));
        }
        return violations;
    }

    private boolean referencedByAnotherModel(SlingModelNode model,
                                             java.util.Collection<SlingModelNode> allModels) {
        String simpleName = model.getFqn().substring(model.getFqn().lastIndexOf('.') + 1);
        for (SlingModelNode other : allModels) {
            if (other == model) {
                continue;
            }
            if (other.getReferencedTypes().contains(model.getFqn())
                    || other.getReferencedTypes().contains(simpleName)) {
                return true;
            }
        }
        return false;
    }

    private String relativePath(RuleContext context, java.nio.file.Path path) {
        return context.relativePath(path);
    }
}
